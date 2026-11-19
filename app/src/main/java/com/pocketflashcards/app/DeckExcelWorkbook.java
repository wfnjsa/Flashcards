package com.pocketflashcards.app;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class DeckExcelWorkbook {
    static final String MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final long MAX_FILE_BYTES = 64L * 1024 * 1024;

    private static final String SHEET_NS =
            "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String OFFICE_REL =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PACKAGE_REL =
            "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";
    private static final String CONTENT_TYPES_NS =
            "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String METADATA_SHEET = "牌組資訊";
    private static final String DECK_NAME = "牌組名稱";
    private static final String DECK_LANGUAGE = "發音語言";
    private static final String WORD = "單字";
    private static final String MEANING = "釋義";
    private static final String EXAMPLE = "例句";
    private static final String READING = "讀音";

    private DeckExcelWorkbook() {}

    // Excel stores each workbook part as XML in a ZIP archive.
    static void write(OutputStream stream, FlashcardStore.Deck deck, List<FlashcardStore.Card> cards)
            throws IOException {
        if (cards.size() >= 1_048_576) {
            throw new IOException("The deck exceeds the Excel worksheet row limit");
        }
        try (ZipOutputStream zip = new ZipOutputStream(stream)) {
            writePart(zip, "[Content_Types].xml", "<Types xmlns=\"" + CONTENT_TYPES_NS + "\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-"
                    + "package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\""
                    + "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + sheetContentType(1) + sheetContentType(2) + "</Types>");
            writePart(zip, "_rels/.rels", "<Relationships xmlns=\"" + PACKAGE_REL + "\">"
                    + relationship("rId1", "officeDocument", "xl/workbook.xml") + "</Relationships>");
            writePart(zip, "xl/workbook.xml", "<workbook xmlns=\"" + SHEET_NS + "\" xmlns:r=\""
                    + OFFICE_REL + "\"><sheets>"
                    + "<sheet name=\"單字卡\" sheetId=\"1\" r:id=\"rId1\"/>"
                    + "<sheet name=\"" + METADATA_SHEET + "\" sheetId=\"2\" r:id=\"rId2\"/>"
                    + "</sheets></workbook>");
            writePart(zip, "xl/_rels/workbook.xml.rels",
                    "<Relationships xmlns=\"" + PACKAGE_REL + "\">"
                    + relationship("rId1", "worksheet", "worksheets/sheet1.xml")
                    + relationship("rId2", "worksheet", "worksheets/sheet2.xml") + "</Relationships>");

            XmlSerializer sheet = startSheet(zip, 1);
            writeRow(sheet, 1, WORD, MEANING, EXAMPLE, READING);
            int row = 2;
            for (FlashcardStore.Card card : cards) {
                writeRow(sheet, row++, card.term, card.definition, card.example, card.reading);
            }
            endSheet(zip, sheet);

            sheet = startSheet(zip, 2);
            writeRow(sheet, 1, DECK_NAME, deck.name);
            writeRow(sheet, 2, DECK_LANGUAGE, deck.language);
            endSheet(zip, sheet);
        }
    }

    private static String sheetContentType(int sheet) {
        return "<Override PartName=\"/xl/worksheets/sheet" + sheet + ".xml\" ContentType=\""
                + "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>";
    }

    private static String relationship(String id, String type, String target) {
        return "<Relationship Id=\"" + id + "\" Type=\"" + OFFICE_REL + "/" + type
                + "\" Target=\"" + target + "\"/>";
    }

    private static void writePart(ZipOutputStream zip, String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + xml).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static XmlSerializer startSheet(ZipOutputStream zip, int number) throws IOException {
        zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + number + ".xml"));
        XmlSerializer xml = Xml.newSerializer();
        xml.setOutput(zip, "UTF-8");
        xml.startDocument("UTF-8", true);
        xml.setPrefix("", SHEET_NS);
        xml.startTag(SHEET_NS, "worksheet");
        xml.startTag(SHEET_NS, "sheetData");
        return xml;
    }

    private static void endSheet(ZipOutputStream zip, XmlSerializer xml) throws IOException {
        xml.endTag(SHEET_NS, "sheetData");
        xml.endTag(SHEET_NS, "worksheet");
        xml.endDocument();
        zip.closeEntry();
    }

    private static void writeRow(XmlSerializer xml, int row, String... cells) throws IOException {
        String number = Integer.toString(row);
        xml.startTag(SHEET_NS, "row").attribute(null, "r", number);
        for (int column = 0; column < cells.length; column++) {
            xml.startTag(SHEET_NS, "c").attribute(null, "r", (char) ('A' + column) + number)
                    .attribute(null, "t", "inlineStr");
            xml.startTag(SHEET_NS, "is");
            xml.startTag(SHEET_NS, "t").attribute(XML_NS, "space", "preserve")
                    .text(encodeText(cells[column]));
            xml.endTag(SHEET_NS, "t");
            xml.endTag(SHEET_NS, "is");
            xml.endTag(SHEET_NS, "c");
        }
        xml.endTag(SHEET_NS, "row");
    }

    static Contents read(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            Map<String, Relationship> packageRelationships = readRelationships(zip, "");
            String workbook = relationshipTarget(packageRelationships, "/officeDocument");
            if (workbook == null) {
                throw unreadableWorkbook();
            }
            Map<String, Relationship> relationships = readRelationships(zip, workbook);
            Map<String, String> sheets = readSheets(zip, workbook, relationships);
            if (sheets.isEmpty()) {
                throw unreadableWorkbook();
            }

            String sharedStringsPart = relationshipTarget(relationships, "/sharedStrings");
            List<String> sharedStrings = readSharedStrings(zip, sharedStringsPart);
            String cardsPart = sheets.values().iterator().next();
            Contents contents = new Contents();
            Columns columns = new Columns();
            readRows(zip, cardsPart, sharedStrings, (number, row) -> {
                if (!columns.areKnown()) {
                    if (row.values().stream().anyMatch(value -> !value.trim().isEmpty())) {
                        columns.readHeader(number, row);
                    }
                } else {
                    columns.readCard(number, row, contents.cards);
                }
            });
            if (!columns.areKnown()) {
                throw new InvalidWorkbook(R.string.excel_headers_required);
            }

            String metadataPart = sheets.get(METADATA_SHEET);
            if (metadataPart != null && !metadataPart.equals(cardsPart)) {
                readRows(zip, metadataPart, sharedStrings, (number, row) -> {
                    String label = cell(row, 0).trim();
                    String value = cell(row, 1).trim();
                    if (DECK_NAME.equals(label)) {
                        contents.name = value;
                    } else if (DECK_LANGUAGE.equals(label)) {
                        contents.language = value;
                    }
                });
            }
            return contents;
        } catch (ZipException | XmlPullParserException | IllegalArgumentException e) {
            throw unreadableWorkbook();
        }
    }

    private static Map<String, Relationship> readRelationships(ZipFile zip, String part)
            throws IOException, XmlPullParserException {
        int slash = part.lastIndexOf('/') + 1;
        String relPart = part.substring(0, slash) + "_rels/" + part.substring(slash) + ".rels";
        Map<String, Relationship> relationships = new LinkedHashMap<>();
        try (InputStream input = openPart(zip, relPart)) {
            XmlPullParser xml = parser(input);
            for (int event = xml.next(); event != XmlPullParser.END_DOCUMENT; event = xml.next()) {
                if (event != XmlPullParser.START_TAG || !"Relationship".equals(xml.getName())
                        || "External".equals(xml.getAttributeValue(null, "TargetMode"))) {
                    continue;
                }
                String id = xml.getAttributeValue(null, "Id");
                String type = xml.getAttributeValue(null, "Type");
                String target = xml.getAttributeValue(null, "Target");
                if (id != null && type != null && target != null) {
                    relationships.put(id, new Relationship(type, resolvePart(part, target)));
                }
            }
        }
        return relationships;
    }

    private static String resolvePart(String part, String target) throws InvalidWorkbook {
        URI uri = URI.create("/" + part).resolve(target).normalize();
        String path = uri.getRawPath();
        if (uri.isAbsolute() || uri.getAuthority() != null || uri.getQuery() != null
                || uri.getFragment() != null || path == null || !path.startsWith("/")
                || path.startsWith("/../") || path.length() == 1) {
            throw unreadableWorkbook();
        }
        return path.substring(1);
    }

    private static String relationshipTarget(Map<String, Relationship> relationships, String type) {
        for (Relationship relationship : relationships.values()) {
            if (relationship.type.endsWith(type)) {
                return relationship.target;
            }
        }
        return null;
    }

    private static Map<String, String> readSheets(ZipFile zip, String workbook,
            Map<String, Relationship> relationships) throws IOException, XmlPullParserException {
        Map<String, String> sheets = new LinkedHashMap<>();
        try (InputStream input = openPart(zip, workbook)) {
            XmlPullParser xml = parser(input);
            for (int event = xml.next(); event != XmlPullParser.END_DOCUMENT; event = xml.next()) {
                if (event != XmlPullParser.START_TAG || !"sheet".equals(xml.getName())) {
                    continue;
                }
                String name = xml.getAttributeValue(null, "name");
                for (int i = 0; i < xml.getAttributeCount(); i++) {
                    if (!"id".equals(xml.getAttributeName(i))) {
                        continue;
                    }
                    Relationship relationship = relationships.get(xml.getAttributeValue(i));
                    if (name != null && relationship != null
                            && relationship.type.endsWith("/worksheet")) {
                        sheets.put(name, relationship.target);
                    }
                }
            }
        }
        return sheets;
    }

    private static List<String> readSharedStrings(ZipFile zip, String part)
            throws IOException, XmlPullParserException {
        List<String> strings = new ArrayList<>();
        if (part != null) {
            try (InputStream input = openPart(zip, part)) {
                XmlPullParser xml = parser(input);
                for (int event = xml.next(); event != XmlPullParser.END_DOCUMENT; event = xml.next()) {
                    if (event == XmlPullParser.START_TAG && "si".equals(xml.getName())) {
                        strings.add(readRichText(xml));
                    }
                }
            }
        }
        return strings;
    }

    private static void readRows(ZipFile zip, String part, List<String> sharedStrings,
            RowReader reader) throws IOException, XmlPullParserException {
        try (InputStream input = openPart(zip, part)) {
            XmlPullParser xml = parser(input);
            int row = 0;
            for (int event = xml.next(); event != XmlPullParser.END_DOCUMENT; event = xml.next()) {
                if (event == XmlPullParser.START_TAG && "row".equals(xml.getName())) {
                    String reference = xml.getAttributeValue(null, "r");
                    row = reference == null ? row + 1 : Integer.parseInt(reference);
                    reader.read(row, readRow(xml, sharedStrings));
                }
            }
        }
    }

    private static Map<Integer, String> readRow(XmlPullParser xml, List<String> sharedStrings)
            throws IOException, XmlPullParserException {
        Map<Integer, String> cells = new LinkedHashMap<>();
        int depth = xml.getDepth();
        int column = 0;
        while (nextInElement(xml, depth)) {
            if (xml.getEventType() == XmlPullParser.START_TAG && "c".equals(xml.getName())) {
                String reference = xml.getAttributeValue(null, "r");
                if (reference != null) {
                    column = columnIndex(reference);
                }
                String value = readCell(xml, sharedStrings);
                if (!value.isEmpty()) {
                    cells.put(column, value);
                }
                column++;
            }
        }
        return cells;
    }

    private static int columnIndex(String reference) throws InvalidWorkbook {
        int index = 0;
        for (int i = 0; i < reference.length(); i++) {
            char letter = reference.charAt(i);
            if (letter < 'A' || letter > 'Z') {
                break;
            }
            index = index * 26 + letter - 'A' + 1;
            if (index > 16_384) {
                throw unreadableWorkbook();
            }
        }
        if (index == 0) {
            throw unreadableWorkbook();
        }
        return index - 1;
    }

    private static String readCell(XmlPullParser xml, List<String> sharedStrings)
            throws IOException, XmlPullParserException {
        String type = xml.getAttributeValue(null, "t");
        String value = "";
        int depth = xml.getDepth();
        while (nextInElement(xml, depth)) {
            if (xml.getEventType() != XmlPullParser.START_TAG || xml.getDepth() != depth + 1) {
                continue;
            }
            if ("is".equals(xml.getName())) {
                value = readRichText(xml);
            } else if ("v".equals(xml.getName())) {
                value = xml.nextText();
            }
        }
        if ("s".equals(type) && !value.isEmpty()) {
            int index = Integer.parseInt(value.trim());
            if (index < 0 || index >= sharedStrings.size()) {
                throw unreadableWorkbook();
            }
            return sharedStrings.get(index);
        }
        if ("e".equals(type)) {
            return "";
        }
        return "str".equals(type) ? decodeText(value) : value;
    }

    private static String readRichText(XmlPullParser xml) throws IOException, XmlPullParserException {
        int depth = xml.getDepth();
        StringBuilder text = new StringBuilder();
        while (nextInElement(xml, depth)) {
            if (xml.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            if ("t".equals(xml.getName())) {
                text.append(decodeText(xml.nextText()));
            } else if ("rPh".equals(xml.getName())) {
                int phoneticDepth = xml.getDepth();
                while (nextInElement(xml, phoneticDepth)) {
                    // Phonetic annotations are separate from the text in the cell.
                }
            }
        }
        return text.toString();
    }

    private static boolean nextInElement(XmlPullParser xml, int depth)
            throws IOException, XmlPullParserException {
        int event = xml.next();
        if (event == XmlPullParser.END_DOCUMENT) {
            throw unreadableWorkbook();
        }
        return event != XmlPullParser.END_TAG || xml.getDepth() != depth;
    }

    private static XmlPullParser parser(InputStream input) throws XmlPullParserException {
        XmlPullParser xml = Xml.newPullParser();
        xml.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        xml.setInput(input, null);
        return xml;
    }

    private static InputStream openPart(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) {
            throw unreadableWorkbook();
        }
        if (entry.getSize() > MAX_FILE_BYTES) {
            throw fileTooLarge();
        }
        return zip.getInputStream(entry);
    }

    private static String cell(Map<Integer, String> row, int column) {
        return row.getOrDefault(column, "");
    }

    // SpreadsheetML encodes XML-incompatible characters as _xHHHH_.
    private static int escapedCharacter(String value, int start) {
        if (start + 6 >= value.length() || value.charAt(start) != '_'
                || value.charAt(start + 1) != 'x' || value.charAt(start + 6) != '_') {
            return -1;
        }
        int code = 0;
        for (int i = start + 2; i < start + 6; i++) {
            int digit = Character.digit(value.charAt(i), 16);
            if (digit < 0) {
                return -1;
            }
            code = code * 16 + digit;
        }
        return code;
    }

    private static String decodeText(String value) {
        if (!value.contains("_x")) {
            return value;
        }
        StringBuilder decoded = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            int escaped = escapedCharacter(value, i);
            if (escaped >= 0) {
                decoded.append((char) escaped);
                i += 6;
            } else {
                decoded.append(value.charAt(i));
            }
        }
        return decoded.toString();
    }

    private static String encodeText(String value) {
        StringBuilder encoded = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '_' && escapedCharacter(value, i) >= 0) {
                encoded.append("_x005F_");
            } else if (c == '\r' || (c < ' ' && c != '\t' && c != '\n')
                    || c == '\uFFFE' || c == '\uFFFF') {
                encoded.append(String.format(Locale.ROOT, "_x%04X_", (int) c));
            } else {
                encoded.append(c);
            }
        }
        return encoded.toString();
    }

    private static InvalidWorkbook unreadableWorkbook() {
        return new InvalidWorkbook(R.string.excel_unreadable);
    }

    static InvalidWorkbook fileTooLarge() {
        return new InvalidWorkbook(R.string.excel_too_large);
    }

    private interface RowReader {
        void read(int rowNumber, Map<Integer, String> row) throws InvalidWorkbook;
    }

    private static final class Columns {
        private int word = -1;
        private int meaning = -1;
        private int example = -1;
        private int reading = -1;

        boolean areKnown() {
            return word >= 0 && meaning >= 0;
        }

        void readHeader(int number, Map<Integer, String> row) throws InvalidWorkbook {
            for (Map.Entry<Integer, String> entry : row.entrySet()) {
                String header = entry.getValue().trim().toLowerCase(Locale.ROOT);
                switch (header) {
                    case WORD:
                    case "英文":
                    case "英文單字":
                    case "word":
                    case "term":
                        word = entry.getKey();
                        break;
                    case MEANING:
                    case "中文":
                    case "翻譯":
                    case "中文翻譯":
                    case "中文釋義":
                    case "meaning":
                    case "definition":
                    case "translation":
                        meaning = entry.getKey();
                        break;
                    case READING:
                    case "讀音（選填）":
                    case "音標":
                    case "假名":
                    case "reading":
                    case "pronunciation":
                    case "phonetic":
                    case "ipa":
                    case "読み方":
                        reading = entry.getKey();
                        break;
                    case EXAMPLE:
                    case "例句（選填）":
                    case "example":
                    case "example sentence":
                    case "sentence":
                        example = entry.getKey();
                        break;
                    default:
                        break;
                }
            }
            if (!areKnown()) {
                throw new InvalidWorkbook(R.string.excel_row_headers_required, number);
            }
        }

        void readCard(int number, Map<Integer, String> row, List<FlashcardStore.CardFields> cards)
                throws InvalidWorkbook {
            String term = cell(row, word);
            String definition = cell(row, meaning);
            String sentence = cell(row, example);
            String pronunciation = cell(row, reading);
            if (term.trim().isEmpty() && definition.trim().isEmpty() && sentence.trim().isEmpty()
                    && pronunciation.trim().isEmpty()) {
                return;
            }
            if (term.trim().isEmpty() || definition.trim().isEmpty()) {
                throw new InvalidWorkbook(R.string.excel_row_card_incomplete, number);
            }
            if (term.length() > FlashcardStore.TERM_LIMIT
                    || definition.length() > FlashcardStore.DEFINITION_LIMIT
                    || sentence.length() > FlashcardStore.EXAMPLE_LIMIT
                    || pronunciation.length() > FlashcardStore.READING_LIMIT) {
                throw new InvalidWorkbook(R.string.excel_row_text_too_long, number);
            }
            cards.add(new FlashcardStore.CardFields(term, definition, sentence, pronunciation));
        }
    }

    private static final class Relationship {
        final String type;
        final String target;

        Relationship(String type, String target) {
            this.type = type;
            this.target = target;
        }
    }

    static final class Contents {
        String name = "";
        String language = "";
        final List<FlashcardStore.CardFields> cards = new ArrayList<>();
    }

    static final class InvalidWorkbook extends IOException {
        final int messageRes;
        final Object[] messageArgs;

        InvalidWorkbook(int messageRes, Object... messageArgs) {
            this.messageRes = messageRes;
            this.messageArgs = messageArgs;
        }
    }
}
