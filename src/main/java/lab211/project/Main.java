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
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * A utility class to merge Markdown tables from multiple files.
 * <p>
 * It takes a main input Markdown file containing a table, finds related "test_result"
 * markdown files in the same directory, and merges their columns into the main table
 * based on the matching ID (assumed to be the first column).
 */
public class Main {

    private static final Parser parser;

    static {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        parser = Parser.builder(options).build();
    }

    /**
     * The entry point of the application.
     *
     * @param args Command line arguments:
     * args[0]: Input markdown file path (required).
     * args[1]: Output markdown file path (optional, default: output.md).
     */
    public static void main(String[] args) {
        String inputFileName = null;
        String outputFileName = "output.md"; // Default is output.md

        //Handle command line args
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
     * Orchestrates the process of reading the input file, discovering test result files,
     * merging the table data, and writing the result to the output file.
     *
     * @param inputFileName  The name of the main input file.
     * @param outputFileName The name of the file to write the merged result to.
     * @throws IOException If an I/O error occurs during file reading or writing.
     */
    private static void mergeMarkdownTables(String inputFileName, String outputFileName) throws IOException {
        Path currentDir = Paths.get("");
        Path inputFile = currentDir.resolve(inputFileName);

        if (!Files.exists(inputFile)) {
            throw new IOException("Input file not found: " + inputFileName);
        }

        //Read input file
        String inputContent = Files.readString(inputFile);
        Table inputTable = parseFirstTable(inputContent);

        if (inputTable == null) {
            throw new IOException("No table found in input file.");
        }

        //Find all test result file to merge
        List<Path> testResultFiles = findTestResultFiles(currentDir, inputFileName, outputFileName);

        if (testResultFiles.isEmpty()) {
            System.out.println("No test result files found to merge.");
            return;
        }

        //Parse all test_result file
        List<Table> testResultTables = new ArrayList<>();
        for (Path file : testResultFiles) {
            try {
                String content = Files.readString(file);
                Table table = parseFirstTable(content);
                if (table != null) {
                    testResultTables.add(table);
                    System.out.println("Loaded table from: " + file.getFileName());
                }
            } catch (Exception e) {
                System.err.println("Error processing file " + file.getFileName() + ": " + e.getMessage());
            }
        }

        // Merge data from test_result into input table based on ID
        Table mergedTable = mergeTablesById(inputTable, testResultTables);

        //Create new markdown content
        String newContent = createMarkdownTable(mergedTable);

        //Write output file
        Files.write(Paths.get(outputFileName), newContent.getBytes());
    }

    /**
     * Scans the directory for markdown files that match the "test_result" pattern or numeric pattern.
     * Files are sorted based on the starting number extracted from the filename.
     *
     * @param directory      The directory to scan.
     * @param inputFileName  The input filename to exclude.
     * @param outputFileName The output filename to exclude.
     * @return A sorted list of Paths to the test result files.
     * @throws IOException If an I/O error occurs during directory walking.
     */
    private static List<Path> findTestResultFiles(Path directory, String inputFileName, String outputFileName) throws IOException {
        List<Path> allFiles = Files.walk(directory, 1)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".md"))
                .filter(path -> !path.getFileName().toString().equals(inputFileName))
                .filter(path -> !path.getFileName().toString().equals(outputFileName))
                .toList();

        // Filter files with test_result pattern
        List<Path> testResultFiles = new ArrayList<>();
        List<Path> skippedFiles = new ArrayList<>();

        for (Path file : allFiles) {
            String fileName = file.getFileName().toString();
            if (fileName.matches(".*Testcase_Result_(\\d+)_(\\d+).*") || hasNumberPattern(fileName)) {
                testResultFiles.add(file);
                System.out.println(file.getFileName() + " passed");
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

        //Sort by start number
        testResultFiles.sort((p1, p2) -> {
            int num1 = extractStartNumber(p1.getFileName().toString());
            int num2 = extractStartNumber(p2.getFileName().toString());
            return Integer.compare(num1, num2);
        });

        return testResultFiles;
    }

    /**
     * Checks if the filename matches the numeric pattern (e.g., file_100_200.md).
     *
     * @param fileName The filename to check.
     * @return true if it matches the pattern, false otherwise.
     */
    private static boolean hasNumberPattern(String fileName) {
        return fileName.matches(".*_\\d+_\\d+\\.md$");
    }

    /**
     * Extracts the starting number from the filename (e.g., returns 100 from file_100_200.md).
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
     * @param markdown The raw markdown string.
     * @return A {@link Table} object representing the first table, or null if no table is found.
     */
    private static Table parseFirstTable(String markdown) {
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
     * Recursively traverses the AST to collect all TableBlock nodes.
     *
     * @param node        The current node being visited.
     * @param tableBlocks The list to populate with found TableBlocks.
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
     * Converts a Flexmark TableBlock AST node into a simple Table record.
     *
     * @param tableBlock The TableBlock node.
     * @return A {@link Table} object containing headers and rows.
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
     * @return A list of strings representing the cell values.
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
     * Helper method to extract text content from a cell node.
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
     * Merges the input table with data from test result tables.
     * The merge is performed based on the first column (ID) of the input table.
     *
     * @param inputTable       The base table.
     * @param testResultTables A list of tables containing additional data.
     * @return A new {@link Table} with merged columns.
     */
    private static Table mergeTablesById(Table inputTable, List<Table> testResultTables) {
        // Create a map for quick access to rows by ID from test_result tables
        Map<String, List<String>> idToTestResultRowMap = new HashMap<>();
        List<String> testResultHeaders = new ArrayList<>();

        // Get headers from test_result tables (assuming all have the same headers)
        if (!testResultTables.isEmpty()) {
            testResultHeaders = testResultTables.getFirst().headers();
        }

        //For each row in test result table map with their id
        for (Table testResultTable : testResultTables) {
            for (List<String> row : testResultTable.rows()) {
                if (!row.isEmpty()) {
                    String id = row.getFirst();
                    idToTestResultRowMap.put(id, row);
                }
            }
        }

        // Create new headers: headers from input + headers from test_result (excluding the ID column)
        List<String> newHeaders = new ArrayList<>(inputTable.headers());
        if (!testResultHeaders.isEmpty()) {
            newHeaders.addAll(testResultHeaders.subList(1, testResultHeaders.size()));
        }

        //Create new rows
        List<List<String>> mergedRows = new ArrayList<>();

        int inputHeaderSize = inputTable.headers().size();

        //
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
     * Converts a Table object into a Markdown table string.
     *
     * @param table The table data to convert.
     * @return A string formatted as a Markdown table.
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
     * A record class to hold table data structure.
     *
     * @param headers The list of column headers.
     * @param rows    The list of rows, where each row is a list of cell values.
     */
    private record Table(List<String> headers, List<List<String>> rows) {

        @Override
        public @NotNull String toString() {
            return "Table{" +
                    "headers=" + headers +
                    ", rows=" + rows +
                    '}';
        }
    }
}