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
import java.util.*;

/**
 * A utility class to merge Markdown tables from multiple files.
 * <p>
 * This application takes a primary input Markdown file containing a table, finds related
 * "test_result" Markdown files in the same directory, extracts their tables, and merges
 * them into a single output file based on the first column (ID) of the tables.
 * </p>
 */
public class Main {

    private static final Parser parser;

    static {
        // Initialize the Flexmark parser with the Tables extension
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        parser = Parser.builder(options).build();
    }

    /**
     * The main entry point of the application.
     *
     * @param args Command line arguments.
     * args[0]: Input markdown file path (required).
     * args[1]: Output markdown file path (optional, default: output.md).
     */
    public static void main(String[] args) {
        String inputFileName = null;
        String outputFileName = "output.md";

        if (args.length >= 1) {
            inputFileName = args[0];
        }

        if (args.length >= 2) {
            outputFileName = args[1];
        }

        if (inputFileName == null) {
            System.err.println("Usage: java -jar app.jar <input-file.md> [output-file.md]");
            System.err.println("If output-file.md is not specified, default is output.md");
            System.exit(1);
        }

        try {
            mergeMarkdownTables(inputFileName, outputFileName);
            System.out.println("Successfully merged markdown tables into " + outputFileName);
        } catch (Exception e) {
            System.err.println("Error merging markdown tables: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Orchestrates the process of reading, parsing, merging, and writing the markdown files.
     *
     * @param inputFileName  The name of the primary input file.
     * @param outputFileName The name of the file to write the results to.
     * @throws IOException If an I/O error occurs during file reading or writing.
     */
    private static void mergeMarkdownTables(String inputFileName, String outputFileName) throws IOException {
        Path currentDir = Paths.get("");
        Path inputFile = currentDir.resolve(inputFileName);

        if (!Files.exists(inputFile)) {
            throw new IOException("Input file not found: " + inputFileName);
        }

        // Read input file and split content
        String inputContent = Files.readString(inputFile);
        FileContent inputFileContent = splitFileContent(inputContent);

        // Parse table from input
        Table inputTable = parseFirstTable(inputFileContent.beforeHorizontalRule());

        if (inputTable == null) {
            throw new IOException("No table found in input file.");
        }

        // Find test_result files to merge
        List<Path> testResultFiles = findTestResultFiles(currentDir, inputFileName, outputFileName);

        if (testResultFiles.isEmpty()) {
            System.out.println("No test result files found to merge.");
            return;
        }

        // Parse all test_result files
        List<Table> testResultTables = new ArrayList<>();
        List<FileContent> testResultContents = new ArrayList<>();

        for (Path file : testResultFiles) {
            try {
                String content = Files.readString(file);
                FileContent fileContent = splitFileContent(content);

                // Parse table
                Table table = parseFirstTable(fileContent.beforeHorizontalRule());
                if (table != null) {
                    testResultTables.add(table);
                    System.out.println("Loaded table from: " + file.getFileName());
                }

                // Save content below ---
                if (fileContent.afterHorizontalRule() != null &&
                        !fileContent.afterHorizontalRule().trim().isEmpty()) {
                    testResultContents.add(new FileContent(
                            file.getFileName().toString(),
                            fileContent.beforeHorizontalRule(),
                            fileContent.afterHorizontalRule()
                    ));
                }

            } catch (Exception e) {
                System.err.println("Error processing file " + file.getFileName() + ": " + e.getMessage());
            }
        }

        // Merge data from test_result into input table based on ID
        Table mergedTable = mergeTablesById(inputTable, testResultTables);

        // Create new markdown content
        StringBuilder newContent = new StringBuilder();

        // Add merged table
        newContent.append(createMarkdownTable(mergedTable)).append("\n\n");

        // Add separator ---
        newContent.append("---\n\n");

        // Add content from input file (part below ---)
        if (inputFileContent.afterHorizontalRule() != null &&
                !inputFileContent.afterHorizontalRule().trim().isEmpty()) {
            newContent.append("\n");
            newContent.append(inputFileContent.afterHorizontalRule()).append("\n\n");
        }

        // Add content from test_result files (part below ---)
        for (FileContent content : testResultContents) {
            newContent.append("\n");
            newContent.append(content.afterHorizontalRule()).append("\n\n");
        }

        // Write output file
        Files.write(Paths.get(outputFileName), newContent.toString().getBytes());
    }

    /**
     * Splits the content of a markdown file into two parts based on the first horizontal rule (---).
     *
     * @param markdown The raw string content of the markdown file.
     * @return A {@link FileContent} object containing the parts before and after the rule.
     */
    private static FileContent splitFileContent(String markdown) {
        // Split content by the first horizontal rule (---)
        String[] sections = markdown.split("\\n\\s*---\\s*\\n", 2);

        if (sections.length == 1) {
            // No horizontal rule found - all content is "before"
            return new FileContent(null, sections[0], null);
        } else {
            // Has horizontal rule - split into before and after
            return new FileContent(null, sections[0], sections[1]);
        }
    }

    /**
     * Scans the directory for valid test result markdown files.
     *
     * @param directory      The directory to search in.
     * @param inputFileName  The input file name to exclude.
     * @param outputFileName The output file name to exclude.
     * @return A list of paths to valid test result files, sorted by their sequence number.
     * @throws IOException If an I/O error occurs during directory walking.
     */
    private static List<Path> findTestResultFiles(Path directory, String inputFileName, String outputFileName) throws IOException {
        List<Path> allFiles = Files.walk(directory, 1)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".md"))
                .filter(path -> !path.getFileName().toString().equals(inputFileName))
                .filter(path -> !path.getFileName().toString().equals(outputFileName))
                .toList();

        // Filter files matching the test_result pattern
        List<Path> testResultFiles = new ArrayList<>();
        List<Path> skippedFiles = new ArrayList<>();

        for (Path file : allFiles) {
            String fileName = file.getFileName().toString();
            if (fileName.matches(".*test_result.*") || hasNumberPattern(fileName)) {
                testResultFiles.add(file);
            } else {
                skippedFiles.add(file);
            }
        }

        if (!skippedFiles.isEmpty()) {
            System.out.println("Skipping files without test_result pattern:");
            for (Path skipped : skippedFiles) {
                System.out.println("  - " + skipped.getFileName());
            }
        }

        // Sort by starting number
        testResultFiles.sort((p1, p2) -> {
            int num1 = extractStartNumber(p1.getFileName().toString());
            int num2 = extractStartNumber(p2.getFileName().toString());
            return Integer.compare(num1, num2);
        });

        return testResultFiles;
    }

    /**
     * Checks if the filename matches a specific numeric pattern (e.g., ending in _1_2.md).
     *
     * @param fileName The name of the file.
     * @return true if it matches the pattern, false otherwise.
     */
    private static boolean hasNumberPattern(String fileName) {
        return fileName.matches(".*_\\d+_\\d+\\.md$");
    }

    /**
     * Extracts the first number sequence from a filename matching the pattern.
     *
     * @param fileName The filename to parse.
     * @return The extracted number, or 0 if extraction fails.
     */
    private static int extractStartNumber(String fileName) {
        try {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(".*_(\\d+)_(\\d+)\\.md$");
            java.util.regex.Matcher matcher = pattern.matcher(fileName);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        } catch (Exception e) {
            // Ignore
        }
        return 0;
    }

    /**
     * Parses the markdown content and extracts the first table found.
     *
     * @param markdown The markdown string.
     * @return A {@link Table} object, or null if no table is found.
     */
    private static Table parseFirstTable(String markdown) {
        if (markdown == null || markdown.trim().isEmpty()) {
            return null;
        }

        Node document = parser.parse(markdown);
        List<TableBlock> tableBlocks = new ArrayList<>();
        collectTableBlocks(document, tableBlocks);

        if (tableBlocks.isEmpty()) {
            return null;
        }

        TableBlock firstTable = tableBlocks.getFirst();
        return extractTable(firstTable);
    }

    /**
     * Recursively traverses the AST to find all TableBlock nodes.
     *
     * @param node        The current node being visited.
     * @param tableBlocks The list to accumulate found TableBlocks.
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
     * Converts a Flexmark TableBlock node into a simplified Table record.
     *
     * @param tableBlock The Flexmark AST node representing the table.
     * @return A populated {@link Table} object.
     */
    private static Table extractTable(TableBlock tableBlock) {
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

        return new Table(headers, rows);
    }

    /**
     * Extracts text content from all cells in a table row.
     *
     * @param row The TableRow node.
     * @return A list of string contents for each cell.
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
     * Extracts text content from a table cell node.
     *
     * @param cell The TableCell node.
     * @return The string content of the cell.
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
     * Merges the input table with multiple test result tables.
     * <p>
     * It uses the first column (assumed to be ID) as the key to join rows.
     * Columns from test result tables are appended to the input table rows.
     * </p>
     *
     * @param inputTable       The base table.
     * @param testResultTables The list of tables to merge into the base table.
     * @return A new merged {@link Table}.
     */
    private static Table mergeTablesById(Table inputTable, List<Table> testResultTables) {
        // Create a map for quick access to rows by ID from test_result tables
        Map<String, List<String>> idToTestResultRowMap = new HashMap<>();
        List<String> testResultHeaders = new ArrayList<>();

        // Get headers from test_result tables (assuming all have the same headers)
        if (!testResultTables.isEmpty()) {
            testResultHeaders = testResultTables.getFirst().headers();
        }

        for (Table testResultTable : testResultTables) {
            for (List<String> row : testResultTable.rows()) {
                if (!row.isEmpty()) {
                    String id = row.getFirst();
                    idToTestResultRowMap.put(id, row);
                }
            }
        }

        // Create new headers: input headers + test_result headers (excluding ID column)
        List<String> newHeaders = new ArrayList<>(inputTable.headers());
        if (!testResultHeaders.isEmpty()) {
            newHeaders.addAll(testResultHeaders.subList(1, testResultHeaders.size()));
        }

        // Create new rows
        List<List<String>> mergedRows = new ArrayList<>();

        for (List<String> inputRow : inputTable.rows()) {
            if (inputRow.isEmpty()) continue;

            String id = inputRow.getFirst();
            List<String> newRow = new ArrayList<>(inputRow);

            // Add data from test_result if available
            List<String> testResultRow = idToTestResultRowMap.get(id);
            if (testResultRow != null) {
                // Add all columns from test_result except the first ID column
                newRow.addAll(testResultRow.subList(1, testResultRow.size()));
            } else {
                // Add empty values for test_result columns if not found
                for (int i = 1; i < testResultHeaders.size(); i++) {
                    newRow.add("");
                }
            }

            mergedRows.add(newRow);
        }

        return new Table(newHeaders, mergedRows);
    }

    /**
     * formatting it as a Markdown string.
     *
     * @param table The table data to format.
     * @return A string representation of the table in Markdown format.
     */
    private static String createMarkdownTable(Table table) {
        StringBuilder markdown = new StringBuilder();

        List<String> headers = table.headers();
        List<List<String>> rows = table.rows();

        // Header
        markdown.append("| ").append(String.join(" | ", headers)).append(" |\n");

        // Separator
        markdown.append("|");
        markdown.append(" --- |".repeat(headers.size()));
        markdown.append("\n");

        // Rows
        for (List<String> row : rows) {
            markdown.append("| ").append(String.join(" | ", row)).append(" |\n");
        }

        return markdown.toString();
    }

    /**
     * A record representing a parsed table structure.
     *
     * @param headers List of header strings.
     * @param rows    List of rows, where each row is a list of strings.
     */
    private record Table(List<String> headers, List<List<String>> rows) {
    }

    /**
     * A record representing the split content of a markdown file.
     *
     * @param fileName             The name of the file.
     * @param beforeHorizontalRule Content before the first '---'.
     * @param afterHorizontalRule  Content after the first '---'.
     */
    private record FileContent(String fileName, String beforeHorizontalRule, String afterHorizontalRule) {
    }
}