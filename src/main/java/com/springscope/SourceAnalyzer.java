
package com.springscope;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

@Component
public class SourceAnalyzer {

    private static final Set<String> BEAN_ANNOTATIONS = Set.of(
            "Component", "Service", "Repository",
            "Controller", "RestController"
    );

    private static final Map<String, String> HTTP_MAPPINGS = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH"
    );

    public record Dependency(String source, String target) {}

    public record Endpoint(
            String controller,
            String httpMethod,
            String path
    ) {}

    public record AnalysisResult(
            Set<String> beans,
            List<Dependency> dependencies,
            List<String> cycles,
            List<Endpoint> endpoints
    ) {}

    public AnalysisResult analyze(Path root) throws Exception {

        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException(
                    "Source directory does not exist: " + root
            );
        }

        Map<String, ClassOrInterfaceDeclaration> beanClasses =
                new LinkedHashMap<>();

        List<CompilationUnit> units = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            units.add(StaticJavaParser.parse(path));
                        } catch (Exception e) {
                            System.err.println(
                                    "Skipping unreadable Java file: " + path
                            );
                        }
                    });
        }

        // Step 1: Detect Spring bean classes.
        for (CompilationUnit unit : units) {
            for (ClassOrInterfaceDeclaration type :
                    unit.findAll(ClassOrInterfaceDeclaration.class)) {

                boolean isBean = type.getAnnotations().stream()
                        .anyMatch(a -> BEAN_ANNOTATIONS.contains(
                                a.getNameAsString()
                        ));

                if (isBean) {
                    String name = type.getNameAsString();
                    beanClasses.putIfAbsent(name, type);
                }
            }
        }

        Set<String> beans = new LinkedHashSet<>(beanClasses.keySet());

        // Step 2: Find constructor-injected dependencies.
        Set<Dependency> dependencies = new LinkedHashSet<>();

        for (var entry : beanClasses.entrySet()) {
            String sourceBean = entry.getKey();
            ClassOrInterfaceDeclaration type = entry.getValue();

            for (ConstructorDeclaration constructor :
                    type.getConstructors()) {

                for (var parameter : constructor.getParameters()) {
                    String targetBean = simpleName(
                            parameter.getType().asString()
                    );

                    if (beans.contains(targetBean)) {
                        dependencies.add(
                                new Dependency(sourceBean, targetBean)
                        );
                    }
                }
            }
        }

        // Step 3: Extract REST endpoints.
        List<Endpoint> endpoints = new ArrayList<>();

        for (CompilationUnit unit : units) {
            for (ClassOrInterfaceDeclaration type :
                    unit.findAll(ClassOrInterfaceDeclaration.class)) {

                if (!type.getAnnotations().stream().anyMatch(a ->
                        a.getNameAsString().equals("RestController")
                                || a.getNameAsString().equals("Controller"))) {
                    continue;
                }

                List<String> classPaths = annotationPaths(
                        type.getAnnotations(), "RequestMapping"
                );

                if (classPaths.isEmpty()) {
                    classPaths = List.of("");
                }

                for (MethodDeclaration method :
                        type.getMethods()) {

                    String httpMethod = null;
                    List<String> methodPaths = new ArrayList<>();

                    for (AnnotationExpr annotation :
                            method.getAnnotations()) {

                        String annotationName =
                                annotation.getNameAsString();

                        if (HTTP_MAPPINGS.containsKey(annotationName)) {
                            httpMethod = HTTP_MAPPINGS.get(annotationName);
                            methodPaths.addAll(
                                    pathsFromAnnotation(annotation)
                            );
                        } else if (annotationName.equals("RequestMapping")) {
                            methodPaths.addAll(
                                    pathsFromAnnotation(annotation)
                            );
                            httpMethod = requestMethod(annotation);
                        }
                    }

                    if (httpMethod == null) {
                        continue;
                    }

                    if (methodPaths.isEmpty()) {
                        methodPaths.add("");
                    }

                    for (String base : classPaths) {
                        for (String path : methodPaths) {
                            endpoints.add(new Endpoint(
                                    type.getNameAsString(),
                                    httpMethod,
                                    joinPaths(base, path)
                            ));
                        }
                    }
                }
            }
        }

        // Step 4: Detect circular dependencies using DFS.
        Map<String, List<String>> graph = new LinkedHashMap<>();

        for (String bean : beans) {
            graph.put(bean, new ArrayList<>());
        }

        for (Dependency dependency : dependencies) {
            graph.get(dependency.source()).add(dependency.target());
        }

        List<String> cycles = findCycles(graph);

        return new AnalysisResult(
                beans,
                new ArrayList<>(dependencies),
                cycles,
                endpoints
        );
    }

    private String simpleName(String type) {
        String name = type.substring(type.lastIndexOf('.') + 1);

        // Handle simple generic types such as List<UserService>.
        int genericStart = name.indexOf('<');
        if (genericStart >= 0) {
            name = name.substring(0, genericStart);
        }

        return name;
    }

    private List<String> annotationPaths(
            NodeList<AnnotationExpr> annotations,
            String name
    ) {
        List<String> result = new ArrayList<>();

        for (AnnotationExpr annotation : annotations) {
            if (annotation.getNameAsString().equals(name)) {
                result.addAll(pathsFromAnnotation(annotation));
            }
        }

        return result;
    }

    private List<String> pathsFromAnnotation(AnnotationExpr annotation) {
        List<String> result = new ArrayList<>();

        if (annotation instanceof SingleMemberAnnotationExpr single) {
            addStringValues(single.getMemberValue(), result);
        }

        if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("value")
                        || pair.getNameAsString().equals("path")) {
                    addStringValues(pair.getValue(), result);
                }
            }
        }

        return result;
    }

    private void addStringValues(
            Expression expression,
            List<String> result
    ) {
        if (expression instanceof StringLiteralExpr literal) {
            result.add(literal.asString());
        } else if (expression instanceof ArrayInitializerExpr array) {
            for (Expression item : array.getValues()) {
                addStringValues(item, result);
            }
        }
    }

    private String requestMethod(AnnotationExpr annotation) {
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("method")) {
                    String value = pair.getValue().toString();

                    if (value.contains("RequestMethod.")) {
                        return value.substring(
                                value.lastIndexOf('.') + 1
                        );
                    }
                }
            }
        }

        return "ANY";
    }

    private String joinPaths(String base, String path) {
        String joined = (base + "/" + path)
                .replaceAll("/+", "/");

        if (joined.isEmpty()) {
            return "/";
        }

        return joined.startsWith("/") ? joined : "/" + joined;
    }

    private List<String> findCycles(
            Map<String, List<String>> graph
    ) {
        List<String> cycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> active = new HashSet<>();
        List<String> stack = new ArrayList<>();

        for (String bean : graph.keySet()) {
            dfs(bean, graph, visited, active, stack, cycles);
        }

        return cycles;
    }

    private void dfs(
            String bean,
            Map<String, List<String>> graph,
            Set<String> visited,
            Set<String> active,
            List<String> stack,
            List<String> cycles
    ) {
        if (active.contains(bean)) {
            int start = stack.indexOf(bean);

            if (start >= 0) {
                List<String> cycle = new ArrayList<>(
                        stack.subList(start, stack.size())
                );
                cycle.add(bean);

                String description = String.join(" -> ", cycle);

                if (!cycles.contains(description)) {
                    cycles.add(description);
                }
            }

            return;
        }

        if (visited.contains(bean)) {
            return;
        }

        visited.add(bean);
        active.add(bean);
        stack.add(bean);

        for (String dependency :
                graph.getOrDefault(bean, List.of())) {
            dfs(dependency, graph, visited, active, stack, cycles);
        }

        stack.remove(stack.size() - 1);
        active.remove(bean);
    }
}
