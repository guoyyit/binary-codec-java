package com.github.guoyyit.binarycodecjava.codec;

import java.util.ArrayList;
import java.util.List;

/**
 * 模式声明文本解析器，产出消息/枚举定义。
 *
 * <pre>
 * message Person {
 *     int32 id = 1;
 *     optional string name = 2 [default = "anon"];
 *     repeated int64 scores = 3;
 *     map&lt;string, int32&gt; attrs = 4;
 *     oneof contact {
 *         string email = 7;
 *         string phone = 8;
 *     }
 *     reserved 100 to 199, 300;
 * }
 * enum Kind { USER = 0; ADMIN = 1; }
 * </pre>
 */
public final class SchemaParser {

    static final int MAX_FIELD_NUMBER = (1 << 29) - 1;

    public static final class ParseResult {
        private final List<MessageDef> messages;
        private final List<EnumDef> enums;

        ParseResult(List<MessageDef> messages, List<EnumDef> enums) {
            this.messages = messages;
            this.enums = enums;
        }

        public List<MessageDef> messages() {
            return messages;
        }

        public List<EnumDef> enums() {
            return enums;
        }
    }

    public ParseResult parse(String schemaText) {
        return new Parser(new Lexer(schemaText).tokenize()).parseSchema();
    }

    private enum TokenKind {
        IDENT, NUMBER, STRING, SYMBOL, EOF
    }

    private record Token(TokenKind kind, String text, int line) {
    }

    private static final class Lexer {
        private final String source;
        private int pos;
        private int line = 1;

        Lexer(String source) {
            this.source = source;
        }

        List<Token> tokenize() {
            List<Token> tokens = new ArrayList<>();
            while (true) {
                skipWhitespaceAndComments();
                if (pos >= source.length()) {
                    tokens.add(new Token(TokenKind.EOF, "", line));
                    return tokens;
                }
                char c = source.charAt(pos);
                if (Character.isLetter(c) || c == '_') {
                    tokens.add(readIdent());
                } else if (Character.isDigit(c) || (c == '-' && pos + 1 < source.length()
                        && Character.isDigit(source.charAt(pos + 1)))) {
                    tokens.add(readNumber());
                } else if (c == '"') {
                    tokens.add(readString());
                } else if ("{}=;<>,[]".indexOf(c) >= 0) {
                    tokens.add(new Token(TokenKind.SYMBOL, String.valueOf(c), line));
                    pos++;
                } else {
                    throw new SchemaException("unexpected character '" + c + "'", line);
                }
            }
        }

        private void skipWhitespaceAndComments() {
            while (pos < source.length()) {
                char c = source.charAt(pos);
                if (c == '\n') {
                    line++;
                    pos++;
                } else if (Character.isWhitespace(c)) {
                    pos++;
                } else if (c == '#') {
                    skipToLineEnd();
                } else if (c == '/' && pos + 1 < source.length() && source.charAt(pos + 1) == '/') {
                    skipToLineEnd();
                } else {
                    return;
                }
            }
        }

        private void skipToLineEnd() {
            while (pos < source.length() && source.charAt(pos) != '\n') {
                pos++;
            }
        }

        private Token readIdent() {
            int start = pos;
            int tokenLine = line;
            while (pos < source.length()) {
                char c = source.charAt(pos);
                if (!Character.isLetterOrDigit(c) && c != '_' && c != '.') {
                    break;
                }
                pos++;
            }
            return new Token(TokenKind.IDENT, source.substring(start, pos), tokenLine);
        }

        private Token readNumber() {
            int start = pos;
            int tokenLine = line;
            if (source.charAt(pos) == '-') {
                pos++;
            }
            while (pos < source.length()
                    && (Character.isDigit(source.charAt(pos)) || source.charAt(pos) == '.')) {
                pos++;
            }
            return new Token(TokenKind.NUMBER, source.substring(start, pos), tokenLine);
        }

        private Token readString() {
            int tokenLine = line;
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= source.length()) {
                    throw new SchemaException("unterminated string literal", tokenLine);
                }
                char c = source.charAt(pos);
                if (c == '"') {
                    pos++;
                    return new Token(TokenKind.STRING, sb.toString(), tokenLine);
                }
                if (c == '\n') {
                    throw new SchemaException("unterminated string literal", tokenLine);
                }
                if (c == '\\') {
                    pos++;
                    if (pos >= source.length()) {
                        throw new SchemaException("unterminated string literal", tokenLine);
                    }
                    char esc = source.charAt(pos);
                    switch (esc) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case '\\' -> sb.append('\\');
                        case '"' -> sb.append('"');
                        default -> throw new SchemaException("unsupported escape '\\" + esc + "'", tokenLine);
                    }
                    pos++;
                } else {
                    sb.append(c);
                    pos++;
                }
            }
        }
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int index;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        ParseResult parseSchema() {
            List<MessageDef> messages = new ArrayList<>();
            List<EnumDef> enums = new ArrayList<>();
            while (peek().kind() != TokenKind.EOF) {
                Token head = peek();
                if (head.kind() == TokenKind.IDENT && head.text().equals("message")) {
                    messages.add(parseMessage());
                } else if (head.kind() == TokenKind.IDENT && head.text().equals("enum")) {
                    enums.add(parseEnum());
                } else {
                    throw error("expected 'message' or 'enum'", head);
                }
            }
            return new ParseResult(messages, enums);
        }

        private MessageDef parseMessage() {
            expectKeyword("message");
            Token nameToken = expectIdent("message name");
            expectSymbol("{");
            List<FieldDef> fields = new ArrayList<>();
            List<int[]> reserved = new ArrayList<>();
            while (!isSymbol("}")) {
                Token head = peek();
                if (head.kind() != TokenKind.IDENT) {
                    throw error("expected field, oneof or reserved", head);
                }
                switch (head.text()) {
                    case "reserved" -> parseReserved(reserved);
                    case "oneof" -> parseOneof(fields);
                    default -> fields.add(parseField(null));
                }
            }
            expectSymbol("}");
            return new MessageDef(nameToken.text(), nameToken.line(), fields, reserved);
        }

        private void parseReserved(List<int[]> reserved) {
            expectKeyword("reserved");
            while (true) {
                Token from = expectNumber("reserved number");
                int start = parseFieldNumber(from);
                int end = start;
                if (peek().kind() == TokenKind.IDENT && peek().text().equals("to")) {
                    next();
                    end = parseFieldNumber(expectNumber("reserved range end"));
                }
                if (end < start) {
                    throw error("reserved range end before start", from);
                }
                reserved.add(new int[]{start, end});
                if (isSymbol(",")) {
                    expectSymbol(",");
                } else {
                    break;
                }
            }
            expectSymbol(";");
        }

        private void parseOneof(List<FieldDef> fields) {
            expectKeyword("oneof");
            Token oneofName = expectIdent("oneof name");
            expectSymbol("{");
            while (!isSymbol("}")) {
                fields.add(parseField(oneofName.text()));
            }
            expectSymbol("}");
        }

        private FieldDef parseField(String oneofName) {
            boolean repeated = false;
            boolean optional = false;
            if (peek().kind() == TokenKind.IDENT) {
                String modifier = peek().text();
                if (modifier.equals("repeated")) {
                    repeated = true;
                    next();
                } else if (modifier.equals("optional")) {
                    optional = true;
                    next();
                }
            }
            if (oneofName != null && (repeated || optional)) {
                throw error("oneof fields cannot be repeated or optional", peek());
            }
            Token typeToken = peek();
            TypeRef type = parseType();
            Token nameToken = expectIdent("field name");
            expectSymbol("=");
            int number = parseFieldNumber(expectNumber("field number"));
            Object defaultValue = parseDefaultClause(type);
            expectSymbol(";");
            if (repeated && type.getKind() == TypeRef.Kind.MAP) {
                throw new SchemaException("map field cannot be repeated", typeToken.line());
            }
            return new FieldDef(nameToken.text(), number, type, repeated, optional, oneofName,
                    defaultValue, nameToken.line());
        }

        private Object parseDefaultClause(TypeRef type) {
            if (!isSymbol("[")) {
                return null;
            }
            expectSymbol("[");
            Token keyword = expectIdent("default");
            if (!keyword.text().equals("default")) {
                throw error("expected 'default'", keyword);
            }
            expectSymbol("=");
            Token valueToken = peek();
            Object value = switch (type.getKind()) {
                case INT32, INT64 -> {
                    Token number = expectNumber("default value");
                    yield parseLong(number);
                }
                case BOOL -> {
                    Token ident = expectIdent("default value");
                    if (!ident.text().equals("true") && !ident.text().equals("false")) {
                        throw error("expected true or false", ident);
                    }
                    yield Boolean.valueOf(ident.text());
                }
                case FLOAT, DOUBLE -> {
                    Token number = expectNumber("default value");
                    yield parseDouble(number);
                }
                case STRING -> {
                    Token string = next();
                    if (string.kind() != TokenKind.STRING) {
                        throw error("expected string literal", string);
                    }
                    yield string.text();
                }
                case ENUM, NAMED -> {
                    Token ident = expectIdent("enum constant name");
                    yield ident.text();
                }
                default -> throw error("default value not allowed for this field type", valueToken);
            };
            expectSymbol("]");
            return value;
        }

        private TypeRef parseType() {
            Token token = peek();
            if (token.kind() != TokenKind.IDENT) {
                throw error("expected type", token);
            }
            next();
            return switch (token.text()) {
                case "int32" -> TypeRef.scalar(TypeRef.Kind.INT32);
                case "int64" -> TypeRef.scalar(TypeRef.Kind.INT64);
                case "bool" -> TypeRef.scalar(TypeRef.Kind.BOOL);
                case "float" -> TypeRef.scalar(TypeRef.Kind.FLOAT);
                case "double" -> TypeRef.scalar(TypeRef.Kind.DOUBLE);
                case "bytes" -> TypeRef.scalar(TypeRef.Kind.BYTES);
                case "string" -> TypeRef.scalar(TypeRef.Kind.STRING);
                case "map" -> {
                    expectSymbol("<");
                    TypeRef keyType = parseType();
                    if (!isAllowedMapKey(keyType)) {
                        throw error("map key type must be int32, int64, bool or string", token);
                    }
                    expectSymbol(",");
                    TypeRef valueType = parseType();
                    if (valueType.getKind() == TypeRef.Kind.MAP) {
                        throw error("map value cannot be a map", token);
                    }
                    expectSymbol(">");
                    yield TypeRef.map(keyType, valueType);
                }
                default -> TypeRef.named(TypeRef.Kind.NAMED, token.text());
            };
        }

        private static boolean isAllowedMapKey(TypeRef type) {
            return switch (type.getKind()) {
                case INT32, INT64, BOOL, STRING -> true;
                default -> false;
            };
        }

        private EnumDef parseEnum() {
            expectKeyword("enum");
            Token nameToken = expectIdent("enum name");
            expectSymbol("{");
            List<EnumDef.Constant> constants = new ArrayList<>();
            while (!isSymbol("}")) {
                Token constantName = expectIdent("enum constant name");
                expectSymbol("=");
                Token valueToken = expectNumber("enum value");
                long value = parseLong(valueToken);
                if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                    throw error("enum value out of int32 range", valueToken);
                }
                expectSymbol(";");
                constants.add(new EnumDef.Constant(constantName.text(), (int) value, constantName.line()));
            }
            expectSymbol("}");
            if (constants.isEmpty()) {
                throw new SchemaException("enum '" + nameToken.text() + "' declares no constants", nameToken.line());
            }
            return new EnumDef(nameToken.text(), nameToken.line(), constants);
        }

        private int parseFieldNumber(Token token) {
            long value = parseLong(token);
            if (value < 1 || value > MAX_FIELD_NUMBER) {
                throw error("field number must be in [1, " + MAX_FIELD_NUMBER + "]", token);
            }
            return (int) value;
        }

        private long parseLong(Token token) {
            try {
                if (token.text().contains(".")) {
                    throw new NumberFormatException("not an integer");
                }
                return Long.parseLong(token.text());
            } catch (NumberFormatException e) {
                throw error("invalid integer '" + token.text() + "'", token);
            }
        }

        private double parseDouble(Token token) {
            try {
                return Double.parseDouble(token.text());
            } catch (NumberFormatException e) {
                throw error("invalid number '" + token.text() + "'", token);
            }
        }

        private Token peek() {
            return tokens.get(index);
        }

        private Token next() {
            return tokens.get(index++);
        }

        private boolean isSymbol(String symbol) {
            Token token = peek();
            return token.kind() == TokenKind.SYMBOL && token.text().equals(symbol);
        }

        private void expectKeyword(String keyword) {
            Token token = peek();
            if (token.kind() != TokenKind.IDENT || !token.text().equals(keyword)) {
                throw error("expected '" + keyword + "'", token);
            }
            next();
        }

        private Token expectIdent(String what) {
            Token token = peek();
            if (token.kind() != TokenKind.IDENT) {
                throw error("expected " + what, token);
            }
            return next();
        }

        private Token expectNumber(String what) {
            Token token = peek();
            if (token.kind() != TokenKind.NUMBER) {
                throw error("expected " + what, token);
            }
            return next();
        }

        private void expectSymbol(String symbol) {
            if (!isSymbol(symbol)) {
                throw error("expected '" + symbol + "'", peek());
            }
            next();
        }

        private SchemaException error(String message, Token token) {
            return new SchemaException(message + ", got '" + token.text() + "'", token.line());
        }
    }
}
