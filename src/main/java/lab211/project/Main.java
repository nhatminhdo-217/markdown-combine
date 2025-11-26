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

public class Main {

    private static final Parser parser;

    static {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        parser = Parser.builder(options).build();
    }

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

    private static void mergeMarkdownTables(String inputFileName, String outputFileName) throws IOException {
        Path currentDir = Paths.get("");
        Path inputFile = currentDir.resolve(inputFileName);

        if (!Files.exists(inputFile)) {
            throw new IOException("Input file not found: " + inputFileName);
        }

        // Đọc file input và tách nội dung
        String inputContent = Files.readString(inputFile);
        FileContent inputFileContent = splitFileContent(inputContent);

        // Parse bảng từ input
        Table inputTable = parseFirstTable(inputFileContent.beforeHorizontalRule());

        if (inputTable == null) {
            throw new IOException("No table found in input file.");
        }

        // Tìm các file test_result để merge
        List<Path> testResultFiles = findTestResultFiles(currentDir, inputFileName, outputFileName);

        if (testResultFiles.isEmpty()) {
            System.out.println("No test result files found to merge.");
            return;
        }

        // Parse tất cả các file test_result
        List<Table> testResultTables = new ArrayList<>();
        List<FileContent> testResultContents = new ArrayList<>();

        for (Path file : testResultFiles) {
            try {
                String content = Files.readString(file);
                FileContent fileContent = splitFileContent(content);

                // Parse bảng
                Table table = parseFirstTable(fileContent.beforeHorizontalRule());
                if (table != null) {
                    testResultTables.add(table);
                    System.out.println("Loaded table from: " + file.getFileName());
                }

                // Lưu nội dung dưới ---
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

        // Merge dữ liệu từ test_result vào input table dựa trên ID
        Table mergedTable = mergeTablesById(inputTable, testResultTables);

        // Tạo nội dung markdown mới
        StringBuilder newContent = new StringBuilder();

        // Thêm bảng đã merge
        newContent.append(createMarkdownTable(mergedTable)).append("\n\n");

        // Thêm dấu --- để phân cách
        newContent.append("---\n\n");

        // Thêm nội dung từ input (phần dưới ---)
        if (inputFileContent.afterHorizontalRule() != null &&
                !inputFileContent.afterHorizontalRule().trim().isEmpty()) {
            newContent.append("<!-- Content from input file -->\n");
            newContent.append(inputFileContent.afterHorizontalRule()).append("\n\n");
        }

        // Thêm nội dung từ các file test_result (phần dưới ---)
        for (FileContent content : testResultContents) {
            newContent.append("<!-- Content from: ").append(content.fileName()).append(" -->\n");
            newContent.append(content.afterHorizontalRule()).append("\n\n");
        }

        // Ghi file output
        Files.write(Paths.get(outputFileName), newContent.toString().getBytes());
    }

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

    private static List<Path> findTestResultFiles(Path directory, String inputFileName, String outputFileName) throws IOException {
        List<Path> allFiles = Files.walk(directory, 1)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".md"))
                .filter(path -> !path.getFileName().toString().equals(inputFileName))
                .filter(path -> !path.getFileName().toString().equals(outputFileName))
                .toList();

        // Lọc các file có pattern test_result
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

        // Sắp xếp theo số bắt đầu
        testResultFiles.sort((p1, p2) -> {
            int num1 = extractStartNumber(p1.getFileName().toString());
            int num2 = extractStartNumber(p2.getFileName().toString());
            return Integer.compare(num1, num2);
        });

        return testResultFiles;
    }

    private static boolean hasNumberPattern(String fileName) {
        return fileName.matches(".*_\\d+_\\d+\\.md$");
    }

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

    private static String extractCellContent(Node cell) {
        StringBuilder content = new StringBuilder();
        Node child = cell.getFirstChild();

        while (child != null) {
            content.append(child.getChars());
            child = child.getNext();
        }

        return content.toString().trim();
    }

    private static Table mergeTablesById(Table inputTable, List<Table> testResultTables) {
        // Tạo map để truy cập nhanh các row theo id từ test_result tables
        Map<String, List<String>> idToTestResultRowMap = new HashMap<>();
        List<String> testResultHeaders = new ArrayList<>();

        // Lấy headers từ test_result tables (giả sử tất cả đều có cùng headers)
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

        // Tạo headers mới: headers từ input + headers từ test_result (bỏ cột id)
        List<String> newHeaders = new ArrayList<>(inputTable.headers());
        if (!testResultHeaders.isEmpty()) {
            newHeaders.addAll(testResultHeaders.subList(1, testResultHeaders.size()));
        }

        // Tạo rows mới
        List<List<String>> mergedRows = new ArrayList<>();

        for (List<String> inputRow : inputTable.rows()) {
            if (inputRow.isEmpty()) continue;

            String id = inputRow.getFirst();
            List<String> newRow = new ArrayList<>(inputRow);

            // Thêm dữ liệu từ test_result nếu có
            List<String> testResultRow = idToTestResultRowMap.get(id);
            if (testResultRow != null) {
                // Thêm tất cả các cột từ test_result trừ cột id đầu tiên
                newRow.addAll(testResultRow.subList(1, testResultRow.size()));
            } else {
                // Thêm giá trị rỗng cho các cột test_result nếu không tìm thấy
                for (int i = 1; i < testResultHeaders.size(); i++) {
                    newRow.add("");
                }
            }

            mergedRows.add(newRow);
        }

        return new Table(newHeaders, mergedRows);
    }

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

    private record Table(List<String> headers, List<List<String>> rows) {
    }

    private record FileContent(String fileName, String beforeHorizontalRule, String afterHorizontalRule) {
    }
}