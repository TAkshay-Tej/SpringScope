
package com.springscope;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class HtmlReportGenerator {

    public Path generate(
            SourceAnalyzer.AnalysisResult result,
            Path sourcePath,
            Path output
    ) throws IOException {

        StringBuilder html = new StringBuilder();

        html.append("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <title>SpringScope Report</title>
                    <style>
                        body {
                            font-family: Arial, sans-serif;
                            margin: 30px;
                            background: #f5f5f5;
                            color: #333;
                        }
                        h1 { color: #2563eb; }
                        h2 {
                            margin-top: 25px;
                            border-bottom: 1px solid #ddd;
                            padding-bottom: 5px;
                        }
                        table {
                            width: 100%;
                            border-collapse: collapse;
                            background: white;
                        }
                        th, td {
                            padding: 10px;
                            border: 1px solid #ddd;
                            text-align: left;
                        }
                        th { background: #e5e7eb; }
                        .summary {
                            background: white;
                            padding: 15px;
                            margin: 10px 0;
                            border-radius: 5px;
                        }
                        .issue { color: #dc2626; }
                    </style>
                </head>
                <body>
                <h1>SpringScope Analysis Report</h1>
                """);

        html.append("<p><b>Source:</b> ")
                .append(escape(sourcePath.toAbsolutePath().toString()))
                .append("</p>");

        html.append("<div class='summary'>")
                .append("<b>Beans:</b> ").append(result.beans().size())
                .append(" &nbsp; | &nbsp; ")
                .append("<b>Dependencies:</b> ").append(result.dependencies().size())
                .append(" &nbsp; | &nbsp; ")
                .append("<b>Circular Dependencies:</b> ").append(result.cycles().size())
                .append(" &nbsp; | &nbsp; ")
                .append("<b>REST Endpoints:</b> ").append(result.endpoints().size())
                .append("</div>");

        // Beans
        html.append("<h2>Spring Beans</h2><table>")
                .append("<tr><th>Bean Name</th></tr>");

        for (String bean : result.beans()) {
            html.append("<tr><td>")
                    .append(escape(bean))
                    .append("</td></tr>");
        }

        html.append("</table>");

        // Dependencies
        html.append("<h2>Constructor Dependencies</h2><table>")
                .append("<tr><th>Source Bean</th><th>Depends On</th></tr>");

        for (SourceAnalyzer.Dependency dependency : result.dependencies()) {
            html.append("<tr><td>")
                    .append(escape(dependency.source()))
                    .append("</td><td>")
                    .append(escape(dependency.target()))
                    .append("</td></tr>");
        }

        html.append("</table>");

        // Circular dependencies
        html.append("<h2>Circular Dependencies</h2>");

        if (result.cycles().isEmpty()) {
            html.append("<p>No circular dependencies detected.</p>");
        } else {
            html.append("<ul class='issue'>");

            for (String cycle : result.cycles()) {
                html.append("<li>")
                        .append(escape(cycle))
                        .append("</li>");
            }

            html.append("</ul>");
        }

        // REST endpoints
        html.append("<h2>REST Endpoints</h2><table>")
                .append("<tr><th>Method</th><th>Path</th><th>Controller</th></tr>");

        for (SourceAnalyzer.Endpoint endpoint : result.endpoints()) {
            html.append("<tr><td>")
                    .append(escape(endpoint.httpMethod()))
                    .append("</td><td>")
                    .append(escape(endpoint.path()))
                    .append("</td><td>")
                    .append(escape(endpoint.controller()))
                    .append("</td></tr>");
        }

        html.append("""
                </table>
                </body>
                </html>
                """);

        Path absoluteOutput = output.toAbsolutePath();
        Files.createDirectories(absoluteOutput.getParent());

        Files.writeString(
                absoluteOutput,
                html.toString(),
                StandardCharsets.UTF_8
        );

        return absoluteOutput;
    }

    private String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
