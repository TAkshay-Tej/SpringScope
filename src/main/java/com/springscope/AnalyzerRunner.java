
package com.springscope;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.nio.file.Path;

@Component
public class AnalyzerRunner implements CommandLineRunner {

    private final SourceAnalyzer sourceAnalyzer;
    private final AnalysisRepository repository;
    private final HtmlReportGenerator reportGenerator;

    public AnalyzerRunner(
            SourceAnalyzer sourceAnalyzer,
            AnalysisRepository repository,
            HtmlReportGenerator reportGenerator) {
        this.sourceAnalyzer = sourceAnalyzer;
        this.repository = repository;
        this.reportGenerator = reportGenerator;
    }

    @Override
    public void run(String... args) throws Exception {
        if (args == null || args.length == 0
                || args[0] == null || args[0].isBlank()) {
            System.out.println("""
                    SpringScope - Spring Boot Source Analyzer

                    Usage:
                    mvn spring-boot:run "-Dspring-boot.run.arguments=D:/YourProject/src/main/java"
                    """);
            return;
        }

        Path sourcePath = Path.of(args[0])
                .toAbsolutePath()
                .normalize();

        if (!sourcePath.toFile().isDirectory()) {
            throw new IllegalArgumentException(
                    "Source directory not found: " + sourcePath);
        }

        System.out.println("Analyzing: " + sourcePath);

        SourceAnalyzer.AnalysisResult result =
                sourceAnalyzer.analyze(sourcePath);

        repository.save(result);

        Path reportPath = reportGenerator.generate(
                result,
                sourcePath,
                Path.of("target", "springscope-report.html")
        );

        System.out.println("Beans found: " + result.beans().size());
        System.out.println(
                "Dependencies found: " + result.dependencies().size());
        System.out.println(
                "Circular dependencies: " + result.cycles().size());
        System.out.println(
                "REST endpoints found: " + result.endpoints().size());

        System.out.println("Analysis saved to PostgreSQL.");
        System.out.println("HTML report: " + reportPath);

        if (Desktop.isDesktopSupported()) {
            try {
                Desktop.getDesktop().browse(reportPath.toUri());
            } catch (Exception exception) {
                System.out.println(
                        "Could not open browser automatically. "
                                + "Open the report path above manually.");
            }
        }

        System.out.println("SpringScope analysis complete.");
    }
}
