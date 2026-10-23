
package com.springscope;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

@Component
public class SourceAnalyzer {

    private static final Set<String> BEAN_ANNOTATIONS = Set.of(
            "Component",
            "Service",
            "Repository",
            "Controller",
            "RestController",
            "Configuration"
    );

    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "RequestMapping",
            "GetMapping",
            "PostMapping",
            "PutMapping",
            "DeleteMapping",
            "PatchMapping"
    );

    private final JavaParser parser;

    public SourceAnalyzer() {
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(
                        ParserConfiguration.LanguageLevel.JAVA_21);

        this.parser = new JavaParser(configuration);
    }

    public AnalysisResult analyze(Path sourceRoot) throws IOException {

        if (sourceRoot == null || !Files.exists(sourceRoot)) {
            throw new IllegalArgumentException(
                    "Source path does not exist: " + sourceRoot);
        }

        if (!Files.isDirectory(sourceRoot)) {
            throw new IllegalArgumentException(
                    "Source path must be a directory: " + sourceRoot);
        }

        List<CompilationUnit> units = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            List<Path> javaFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();

            for (Path javaFile : javaFiles) {
                ParseResult<CompilationUnit> parsed = parser.parse(javaFile);

                if (parsed.getResult().isPresent()) {
                    units.add(parsed.getResult().get());
                } else {
                    System.err.println(
                            "Could not parse Java file: " + javaFile);
                }
            }
        }

        Set<String> beans = new TreeSet<>();
        List<Dependency> dependencies = new ArrayList<>();
        List<Endpoint> endpoints = new ArrayList<>();

        Map<String, String> simpleToQualified = new HashMap<>();
        Map<String, List<String>> constructorParameters = new HashMap<>();

        // Pass 1: detect Spring beans and constructor parameters.
        for (CompilationUnit unit : units) {

            String packageName = unit.getPackageDeclaration()
                    .map(p -> p.getNameAsString())
                    .orElse("");

            for (ClassOrInterfaceDeclaration declaration :
                    unit.findAll(ClassOrInterfaceDeclaration.class)) {

                String className = declaration.getNameAsString();

                String qualifiedName = packageName.isEmpty()
                        ? className
                        : packageName + "." + className;

                if (!hasBeanAnnotation(declaration)) {
                    continue;
                }

                beans.add(qualifiedName);
                simpleToQualified.put(className, qualifiedName);

                List<String> parameters = new ArrayList<>();

                for (ConstructorDeclaration constructor :
                        declaration.getConstructors()) {

                    constructor.getParameters().forEach(parameter ->
                            parameters.add(
                                    parameter.getType().asString()));
                }

                constructorParameters.put(qualifiedName, parameters);
            }
        }

        // Pass 2: match constructor parameters to known beans.
        for (Map.Entry<String, List<String>> entry :
                constructorParameters.entrySet()) {

            String sourceBean = entry.getKey();

            for (String parameterType : entry.getValue()) {

                String simpleType = getSimpleTypeName(parameterType);
                String targetBean = simpleToQualified.get(simpleType);

                if (targetBean != null) {
                    dependencies.add(
                            new Dependency(sourceBean, targetBean));
                }
            }
        }

        // Pass 3: detect REST endpoints.
        for (CompilationUnit unit : units) {

            String packageName = unit.getPackageDeclaration()
                    .map(p -> p.getNameAsString())
                    .orElse("");

            for (ClassOrInterfaceDeclaration declaration :
                    unit.findAll(ClassOrInterfaceDeclaration.class)) {

                if (!isController(declaration)) {
                    continue;
                }

                String controllerName = packageName.isEmpty()
                        ? declaration.getNameAsString()
                        : packageName + "." + declaration.getNameAsString();

                List<String> classPaths =
                        getMappingPaths(declaration.getAnnotations());

                for (MethodDeclaration method :
                        declaration.getMethods()) {

                    for (AnnotationExpr annotation :
                            method.getAnnotations()) {

                        String annotationName =
                                annotation.getName().getIdentifier();

                        if (!MAPPING_ANNOTATIONS.contains(annotationName)) {
                            continue;
                        }

                        String httpMethod =
                                getHttpMethod(annotationName, annotation);

                        List<String> methodPaths =
                                getMappingPaths(List.of(annotation));

                        for (String classPath : classPaths) {
                            for (String methodPath : methodPaths) {

                                String fullPath =
                                        combinePath(classPath, methodPath);

                                endpoints.add(new Endpoint(
                                        httpMethod,
                                        fullPath,
                                        controllerName,
                                        method.getNameAsString()
                                ));
                            }
                        }
                    }
                }
            }
        }

        List<String> cycles = findCycles(beans, dependencies);

        dependencies.sort(
                Comparator.comparing(Dependency::source)
                        .thenComparing(Dependency::target));

        endpoints.sort(
                Comparator.comparing(Endpoint::controller)
                        .thenComparing(Endpoint::path)
                        .thenComparing(Endpoint::httpMethod));

        return new AnalysisResult(
                beans,
                dependencies,
                cycles,
                endpoints
        );
    }

    private String getSimpleTypeName(String type) {

        String cleaned = type.trim();

        // Remove generic parameters, e.g. List<MyService> -> List.
        cleaned = cleaned.replaceAll("<.*>", "");

        // Remove array suffixes.
        while (cleaned.endsWith("[]")) {
            cleaned = cleaned.substring(0, cleaned.length() - 2);
        }

        // Remove package name, e.g. com.example.MyService -> MyService.
        int lastDot = cleaned.lastIndexOf('.');

        if (lastDot >= 0) {
            cleaned = cleaned.substring(lastDot + 1);
        }

        return cleaned.trim();
    }

    private boolean hasBeanAnnotation(
            NodeWithAnnotations<?> declaration) {

        for (AnnotationExpr annotation : declaration.getAnnotations()) {
            if (BEAN_ANNOTATIONS.contains(
                    annotation.getName().getIdentifier())) {
                return true;
            }
        }

        return false;
    }

    private boolean isController(
            ClassOrInterfaceDeclaration declaration) {

        for (AnnotationExpr annotation : declaration.getAnnotations()) {
            String name = annotation.getName().getIdentifier();

            if (name.equals("RestController")
                    || name.equals("Controller")) {
                return true;
            }
        }

        return false;
    }

    private List<String> getMappingPaths(
            NodeWithAnnotations<?> declaration) {

        for (AnnotationExpr annotation : declaration.getAnnotations()) {

            String name = annotation.getName().getIdentifier();

            if (name.equals("RequestMapping")) {
                return extractPaths(annotation);
            }
        }

        return List.of("");
    }

    private List<String> getMappingPaths(
            List<AnnotationExpr> annotations) {

        for (AnnotationExpr annotation : annotations) {
            if (MAPPING_ANNOTATIONS.contains(
                    annotation.getName().getIdentifier())) {
                return extractPaths(annotation);
            }
        }

        return List.of("");
    }

    private List<String> extractPaths(AnnotationExpr annotation) {

        if (annotation instanceof SingleMemberAnnotationExpr single) {
            return extractStringValues(single.getMemberValue());
        }

        if (annotation instanceof NormalAnnotationExpr normal) {

            for (var pair : normal.getPairs()) {

                String name = pair.getNameAsString();

                if (name.equals("value") || name.equals("path")) {
                    return extractStringValues(pair.getValue());
                }
            }
        }

        return List.of("");
    }

    private List<String> extractStringValues(Expression expression) {

        if (expression instanceof StringLiteralExpr literal) {
            return List.of(literal.asString());
        }

        if (expression instanceof ArrayInitializerExpr array) {

            List<String> values = new ArrayList<>();

            for (Expression value : array.getValues()) {
                if (value instanceof StringLiteralExpr literal) {
                    values.add(literal.asString());
                }
            }

            return values.isEmpty() ? List.of("") : values;
        }

        return List.of("");
    }

    private String getHttpMethod(
            String annotationName,
            AnnotationExpr annotation) {

        return switch (annotationName) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "DeleteMapping" -> "DELETE";
            case "PatchMapping" -> "PATCH";
            default -> getRequestMethod(annotation);
        };
    }

    private String getRequestMethod(AnnotationExpr annotation) {

        if (!(annotation instanceof NormalAnnotationExpr normal)) {
            return "REQUEST";
        }

        for (var pair : normal.getPairs()) {

            if (!pair.getNameAsString().equals("method")) {
                continue;
            }

            String value = pair.getValue().toString();

            if (value.contains("GET")) return "GET";
            if (value.contains("POST")) return "POST";
            if (value.contains("PUT")) return "PUT";
            if (value.contains("DELETE")) return "DELETE";
            if (value.contains("PATCH")) return "PATCH";
            if (value.contains("OPTIONS")) return "OPTIONS";
            if (value.contains("HEAD")) return "HEAD";
        }

        return "REQUEST";
    }

    private String combinePath(String base, String method) {

        String combined = (base + "/" + method)
                .replaceAll("/+", "/");

        if (!combined.startsWith("/")) {
            combined = "/" + combined;
        }

        if (combined.length() > 1 && combined.endsWith("/")) {
            combined = combined.substring(0, combined.length() - 1);
        }

        return combined;
    }

    private List<String> findCycles(
            Set<String> beans,
            List<Dependency> dependencies) {

        Map<String, List<String>> graph = new HashMap<>();

        for (String bean : beans) {
            graph.put(bean, new ArrayList<>());
        }

        for (Dependency dependency : dependencies) {
            graph.computeIfAbsent(
                            dependency.source(),
                            ignored -> new ArrayList<>())
                    .add(dependency.target());
        }

        Set<String> visited = new HashSet<>();
        Set<String> active = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> cycles = new LinkedHashSet<>();

        for (String bean : beans) {
            detectCycle(
                    bean, graph, visited, active, stack, cycles);
        }

        return new ArrayList<>(cycles);
    }

    private void detectCycle(
            String bean,
            Map<String, List<String>> graph,
            Set<String> visited,
            Set<String> active,
            Deque<String> stack,
            Set<String> cycles) {

        if (active.contains(bean)) {

            List<String> cycle = new ArrayList<>();
            boolean recording = false;

            for (String item : stack) {
                if (item.equals(bean)) {
                    recording = true;
                }

                if (recording) {
                    cycle.add(item);
                }
            }

            cycle.add(bean);

            if (!cycle.isEmpty()) {
                cycles.add(String.join(" -> ", cycle));
            }

            return;
        }

        if (visited.contains(bean)) {
            return;
        }

        visited.add(bean);
        active.add(bean);
        stack.addLast(bean);

        for (String dependency :
                graph.getOrDefault(bean, Collections.emptyList())) {

            detectCycle(
                    dependency,
                    graph,
                    visited,
                    active,
                    stack,
                    cycles);
        }

        stack.removeLast();
        active.remove(bean);
    }

    public record Dependency(
            String source,
            String target) {}

    public record Endpoint(
            String httpMethod,
            String path,
            String controller,
            String methodName) {}

    public record AnalysisResult(
            Set<String> beans,
            List<Dependency> dependencies,
            List<String> cycles,
            List<Endpoint> endpoints) {}
}
