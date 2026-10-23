
package com.springscope;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class AnalyzerRunner implements CommandLineRunner {

    private final SourceAnalyzer sourceAnalyzer;
    private final AnalysisRepository repository;

    public AnalyzerRunner(SourceAnalyzer sourceAnalyzer, AnalysisRepository repository) {
        this.sourceAnalyzer = sourceAnalyzer;
        this.repository = repository;
    }

    @Override
    public void run(String... args) throws Exception {
        if (args == null || args.length == 0 || args[0] == null || args[0].isBlank()) {
            printUsage();
            return;
        }

        Path sourcePath = Path.of(args[0]);

        System.out.println("\n=== SpringScope Analysis ===");
        System.out.println("Source: " + sourcePath.toAbsolutePath());

        SourceAnalyzer.AnalysisResult result = sourceAnalyzer.analyze(sourcePath);

        printSection("Spring Beans", result.beans());
        printDependencies(result.dependencies());
        printCycles(result.cycles());
        printEndpoints(result.endpoints());

        System.out.println("\n--- Statistics ---");
        System.out.println("Beans: " + result.beans().size());
        System.out.println("Dependencies: " + result.dependencies().size());
        System.out.println("Cycles: " + result.cycles().size());
        System.out.println("Endpoints: " + result.endpoints().size());

        repository.save(result);

        System.out.println("\nAnalysis saved to the database.");
        System.out.println("=== Analysis Complete ===");
    }

    private void printUsage() {
        System.out.println("""

                SpringScope - Spring Boot Source Analyzer

                Usage:
                mvn spring-boot:run -Dspring-boot.run.arguments="PATH"

                Example:
                mvn spring-boot:run -Dspring-boot.run.arguments="D:/MyApp/src/main/java"
                """);
    }

    private void printSection(String title, Iterable<?> items) {
        System.out.println("\n--- " + title + " ---");
        items.forEach(System.out::println);
    }

    private void printDependencies(Iterable<SourceAnalyzer.Dependency> dependencies) {
        System.out.println("\n--- Constructor Dependencies ---");
        dependencies.forEach(dependency ->
                System.out.println(dependency.source() + " -> " + dependency.target())
        );
    }

    private void printCycles(Iterable<String> cycles) {
        System.out.println("\n--- Circular Dependencies ---");
        if (cycles.iterator().hasNext()) {
            cycles.forEach(System.out::println);
        } else {
            System.out.println("No circular dependencies detected.");
        }
    }

    private void printEndpoints(Iterable<SourceAnalyzer.Endpoint> endpoints) {
        System.out.println("\n--- REST Endpoints ---");
        endpoints.forEach(endpoint ->
                System.out.println(endpoint.httpMethod() + " " + endpoint.path() + " [" + endpoint.controller() + "]")
        );
    }
}
