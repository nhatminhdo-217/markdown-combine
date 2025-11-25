package lab211.project;

import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableBody;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableHead;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The main class for the Markdown merging utility.
 * <p>
 * This utility scans a directory for Markdown files, parses them to extract tables
 * and content separated by horizontal rules, and merges them into a single output file.
 * It specifically handles merging tables with identical structures and appending
 * non-table content sequentially.
 * </p>
 */
public class Main {

    /**
     * The Markdown parser instance configured with the Tables extension.
     * This static instance is reused for parsing all files to improve performance.
     */
    private static final Parser parser;

    static {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        parser = Parser.builder(options).build();
    }

    /**
     * The entry point of the application.
     *
     * @param args Command line arguments. The first argument is optional and specifies
     * the output filename (defaults to "output.md" if not provided).
     */
    public static void main(String[] args) {
        String outputFileName = "output.md";

        if (args.length > 0) {
            outputFileName = args[0];
            if (!outputFileName.endsWith(".md")) {
                outputFileName += ".md";
            }
        }

        try {
            mergeMarkdownContent(outputFileName);
            System.out.println("Successfully merged markdown content into " + outputFileName);
        } catch (Exception e) {
            System.err.println("Error merging markdown content: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Finds all Markdown files in the current directory, parses them, merges their tables and content,
     * and writes the result to an output file.
     * <p>
     * This method orchestrates the entire merging process. It iterates through all found markdown files,
     * splits their content by a horizontal rule, and processes the table and non-table sections separately.
     * It ensures that all merged tables share the same structure (headers).
     * </p>
     *
     * @param outputFileName The name of the file to write the merged content to.
     * @throws IOException if an I/O error occurs when reading files or writing the output file.
     */
    private static void mergeMarkdownContent(String outputFileName) throws IOException {
        Path currentDir = Paths.get("");
        List<Path> markdownFiles = findMarkdownFiles(currentDir, outputFileName);

        if (markdownFiles.isEmpty()) {
            System.out.println("No markdown files found in current directory.");
            return;
        }

        List<TableData> allTableData = new ArrayList<>();
        List<FileContent> allContent = new ArrayList<>();
        TableStructure tableStructure = null;

        // Process all files
        for (Path file : markdownFiles) {
            try {
                String content = Files.readString(file);
                FileSections sections = splitFileContent(content, file.getFileName().toString());

                // Process tables (before ---)
                if (sections.beforeHorizontalRule() != null && !sections.beforeHorizontalRule().trim().isEmpty()) {
                    List<TableData> tables = parseTablesFromMarkdown(sections.beforeHorizontalRule(), file.getFileName().toString());

                    if (!tables.isEmpty()) {
                        TableData firstTable = tables.getFirst();

                        // Set table structure from first file
                        if (tableStructure == null) {
                            tableStructure = firstTable.structure();
                        }

                        // Verify structure matches
                        if (firstTable.structure().equals(tableStructure)) {
                            allTableData.addAll(tables);
                        } else {
                            System.err.println("Warning: Table structure in " + file.getFileName() +
                                    " doesn't match. Skipping tables from this file.");
                        }
                    }
                }

                // Process content after ---
                if (sections.afterHorizontalRule() != null && !sections.afterHorizontalRule().trim().isEmpty()) {
                    allContent.add(new FileContent(file.getFileName().toString(), sections.afterHorizontalRule()));
                }

            } catch (Exception e) {
                System.err.println("Error processing file " + file.getFileName() + ": " + e.getMessage());
            }
        }

        // Generate merged output
        StringBuilder mergedContent = new StringBuilder();

        // Add merged table (if any)
        if (!allTableData.isEmpty()) {
            mergedContent.append(createMergedTable(allTableData, tableStructure)).append("\n\n");
        }

        // Add separator
        if (!allTableData.isEmpty() && !allContent.isEmpty()) {
            mergedContent.append("---\n\n");
        }

        // Add content after --- from all files
        for (FileContent content : allContent) {
            mergedContent.append("\n");
            mergedContent.append(content.content()).append("\n\n");
        }

        if (mergedContent.isEmpty()) {
            System.out.println("No content found to merge.");
            return;
        }

        Files.write(Paths.get(outputFileName), mergedContent.toString().getBytes());
    }

    /**
     * Scans a given directory for Markdown files (`.md`), excluding the specified output file.
     * The search is limited to the top level of the given directory.
     * <p>
     * Files are sorted based on a numeric sequence extracted from their filenames
     * using {@link #extractStartNumber(String)}.
     * </p>
     *
     * @param directory      The directory to search in.
     * @param outputFileName The name of the output file to exclude from the search results.
     * @return A sorted list of {@code Path} objects representing the found Markdown files.
     * @throws IOException if an I/O error occurs during file system traversal.
     */
    private static List<Path> findMarkdownFiles(Path directory, String outputFileName) throws IOException {

        List<Path> files = Files.walk(directory, 1)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".md"))
                .filter(path -> !path.getFileName().toString().equals(outputFileName))
                .toList();

        List<PathWithNumber> validPaths = new ArrayList<>();

        for (Path path : files) {
            String fileName = path.getFileName().toString();
            Integer startNumber = extractStartNumber(fileName);

            if (startNumber != null) {
                validPaths.add(new PathWithNumber(path, startNumber));
            }
        }

        validPaths.sort(Comparator.comparingInt(PathWithNumber::number));

        return validPaths.stream()
                .map(PathWithNumber::path)
                .collect(Collectors.toList());
    }

    /**
     * Extracts a specific numeric identifier from a filename based on a regex pattern.
     * <p>
     * The method expects the filename to end with a pattern like {@code _num1_num2.md}
     * (e.g., {@code file_10_01.md}). It extracts and returns the first number group (num1).
     * </p>
     *
     * @param fileName The name of the file to parse.
     * @return The integer value of the extracted number, or {@code null} if the pattern does not match.
     */
    private static Integer extractStartNumber(String fileName) {
        try {
            // Pattern matches files ending in: _(digits)_(digits).md
            Pattern pattern = java.util.regex.Pattern.compile(".*_(\\d+)_(\\d+)\\.md$");
            Matcher matcher = pattern.matcher(fileName);

            if (matcher.find() && matcher.groupCount() >= 1) {
                System.out.println(matcher.group(1));
                return Integer.parseInt(matcher.group(1));
            }

            System.err.println("Invalid format file name: " + fileName);
            return null;
        } catch (Exception e) {
            System.err.println("Error extracting number from file: " + fileName + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * Splits the content of a Markdown file into two sections based on the first horizontal rule (`---`).
     *
     * @param markdown The full string content of the Markdown file.
     * @param fileName The name of the file, used for context in the returned record.
     * @return A {@code FileSections} record containing the content before and after the horizontal rule.
     * If no rule is found, the {@code afterHorizontalRule} component will be {@code null}.
     */
    private static FileSections splitFileContent(String markdown, String fileName) {
        // Split content by the first horizontal rule (---)
        String[] sections = markdown.split("\\n\\s*---\\s*\\n", 2);

        if (sections.length == 1) {
            // No horizontal rule found - all content is "before"
            return new FileSections(fileName, sections[0], null);
        } else {
            // Has horizontal rule - split into before and after
            return new FileSections(fileName, sections[0], sections[1]);
        }
    }

    /**
     * Parses a given Markdown string to find and extract all tables using the flexmark-java library.
     * It traverses the document's abstract syntax tree (AST) to locate table blocks.
     *
     * @param markdown The Markdown content to parse.
     * @param fileName The source file name, used for context in the returned data.
     * @return A list of {@code TableData} records, one for each table found in the content.
     */
    private static List<TableData> parseTablesFromMarkdown(String markdown, String fileName) {
        List<TableData> tables = new ArrayList<>();
        Node document = parser.parse(markdown);

        // Find all table blocks in the document
        List<TableBlock> tableBlocks = new ArrayList<>();
        collectTableBlocks(document, tableBlocks);

        for (TableBlock tableBlock : tableBlocks) {
            TableData tableData = extractTableData(tableBlock, fileName);
            if (tableData != null) {
                tables.add(tableData);
            }
        }

        return tables;
    }

    /**
     * Recursively traverses the abstract syntax tree (AST) of a parsed Markdown document
     * to find all {@code TableBlock} nodes.
     *
     * @param node        The current AST node to start the search from.
     * @param tableBlocks The list to which found {@code TableBlock} nodes are added.
     */
    private static void collectTableBlocks(Node node, List<TableBlock> tableBlocks) {
        if (node instanceof TableBlock) {
            tableBlocks.add((TableBlock) node);
        }

        Node child = node.getFirstChild();
        while (child != null) {
            collectTableBlocks(child, tableBlocks);
            child = child.getNext();
        }
    }

    /**
     * Extracts the headers and rows from a single {@code TableBlock} AST node.
     *
     * @param tableBlock The {@code TableBlock} node from the AST.
     * @param fileName   The name of the source file for context.
     * @return A {@code TableData} record containing the table's structure and rows.
     * Returns {@code null} if no headers are found in the table.
     */
    private static TableData extractTableData(TableBlock tableBlock, String fileName) {
        List<String> headers = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();

        // Extract headers from table head
        TableHead tableHead = (TableHead) tableBlock.getFirstChild();
        if (tableHead != null) {
            TableRow headerRow = (TableRow) tableHead.getFirstChild();
            if (headerRow != null) {
                headers = extractRowCells(headerRow);
            }
        }

        // Extract rows from table body
        TableBody tableBody = null;
        Node child = tableBlock.getFirstChild();
        while (child != null) {
            if (child instanceof TableBody) {
                tableBody = (TableBody) child;
                break;
            }
            child = child.getNext();
        }

        if (tableBody != null) {
            TableRow row = (TableRow) tableBody.getFirstChild();
            while (row != null) {
                List<String> rowData = extractRowCells(row);
                if (!rowData.isEmpty()) {
                    rows.add(rowData);
                }
                row = (TableRow) row.getNext();
            }
        }

        if (!headers.isEmpty()) {
            return new TableData(fileName, new TableStructure(headers), rows);
        }

        return null;
    }

    /**
     * Extracts the text content of all cells within a single {@code TableRow} node.
     *
     * @param row The {@code TableRow} node from the AST.
     * @return A list of strings, where each string is the trimmed content of a cell in the row.
     */
    private static List<String> extractRowCells(TableRow row) {
        List<String> cells = new ArrayList<>();
        TableCell cell = (TableCell) row.getFirstChild();

        while (cell != null) {
            String cellContent = extractCellContent(cell);
            cells.add(cellContent.trim());
            cell = (TableCell) cell.getNext();
        }

        return cells;
    }

    /**
     * Extracts the raw text content from a cell node by concatenating the text of all its children.
     *
     * @param cell The {@code TableCell} node (or any other node) from the AST.
     * @return A string containing the concatenated and trimmed text of all child nodes.
     */
    private static String extractCellContent(Node cell) {
        StringBuilder content = new StringBuilder();
        Node child = cell.getFirstChild();

        while (child != null) {
            content.append(child.getChars());
            child = child.getNext();
        }

        return content.toString().trim();
    }

    /**
     * Generates a single Markdown table string by merging the rows from multiple tables that share a common structure.
     *
     * @param tables    A list of {@code TableData} objects to merge. All tables are expected to have the same structure.
     * @param structure The common {@code TableStructure} (headers) for the merged table.
     * @return A string representing the complete merged Markdown table.
     */
    private static String createMergedTable(List<TableData> tables, TableStructure structure) {
        StringBuilder merged = new StringBuilder();

        // Create header
        List<String> headers = structure.headers();
        merged.append("| ").append(String.join(" | ", headers)).append(" |\n");

        // Create separator
        merged.append("|");
        merged.append(" --- |".repeat(headers.size()));
        merged.append("\n");

        // Add all rows from all tables
        for (TableData table : tables) {
            for (List<String> row : table.rows()) {
                merged.append("| ").append(String.join(" | ", row)).append(" |\n");
            }
        }

        return merged.toString();
    }

    /**
     * A record to hold the content of a file, split into two parts by a horizontal rule (`---`).
     *
     * @param fileName             The name of the source file.
     * @param beforeHorizontalRule The content before the first horizontal rule.
     * @param afterHorizontalRule  The content after the first horizontal rule. Can be {@code null}.
     */
    private record FileSections(String fileName, String beforeHorizontalRule, String afterHorizontalRule) {
    }

    /**
     * A record to represent a single parsed table from a Markdown file.
     *
     * @param sourceFile The name of the file the table was parsed from.
     * @param structure  The {@code TableStructure} (headers) of the table.
     * @param rows       A list of lists of strings, representing the data rows of the table.
     */
    private record TableData(String sourceFile, TableStructure structure, List<List<String>> rows) {
    }

    /**
     * A record to represent the structure (headers) of a Markdown table.
     * This is used for comparison to ensure that different tables can be merged.
     *
     * @param headers A list of strings representing the column headers.
     */
    private record TableStructure(List<String> headers) {
        /**
         * Constructor creates a defensive copy of the headers list to ensure immutability.
         * @param headers The list of header strings.
         */
        private TableStructure(List<String> headers) {
            this.headers = new ArrayList<>(headers);
        }

        /**
         * Compares this table structure to another object.
         * Equality is defined based on the content and order of the headers.
         *
         * @param obj The object to compare with.
         * @return {@code true} if the headers are identical, {@code false} otherwise.
         */
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            TableStructure that = (TableStructure) obj;
            return Objects.equals(headers, that.headers);
        }
    }

    /**
     * A record to hold the non-table content from a file (typically the part after the horizontal rule).
     *
     * @param fileName The name of the source file.
     * @param content  The string content from the file.
     */
    private record FileContent(String fileName, String content) {
    }

    /**
     * A helper record used for sorting file paths based on an extracted number.
     *
     * @param path   The file path.
     * @param number The number extracted from the filename for sorting purposes.
     */
    private record PathWithNumber(Path path, int number) {
    }
}