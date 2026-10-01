package com.github.guoyyit.binarycodecjava.parser;

import com.github.guoyyit.binarycodecjava.codec.CodecLimits;
import com.github.guoyyit.binarycodecjava.model.EnumDescriptor;
import com.github.guoyyit.binarycodecjava.model.FieldDescriptor;
import com.github.guoyyit.binarycodecjava.model.FieldLabel;
import com.github.guoyyit.binarycodecjava.model.MessageDescriptor;
import com.github.guoyyit.binarycodecjava.model.OneofDescriptor;
import com.github.guoyyit.binarycodecjava.model.ScalarType;
import com.github.guoyyit.binarycodecjava.model.Schema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SchemaParser {

    public Schema parse(String text) {
        if (text == null) {
            throw new SchemaException("schema text must not be null", 1);
        }
        Lexer lexer = new Lexer(text);
        ParseState state = new ParseState();

        lexer.expectKeyword("schema");
        Token schemaName = lexer.expect(TokenType.IDENT);
        lexer.expect(';');
        lexer.expectKeyword("version");
        Token version = lexer.expect(TokenType.STRING);
        lexer.expect(';');

        while (lexer.peek().type != TokenType.EOF) {
            Token keyword = lexer.expect(TokenType.IDENT);
            if (keyword.text.equals("message")) {
                state.messages.add(parseMessage(lexer, state, schemaName.text, null));
            } else if (keyword.text.equals("enum")) {
                state.enums.add(parseEnum(lexer, state, schemaName.text, null));
            } else {
                throw new SchemaException("expected 'message' or 'enum', got '" + keyword.text + "'",
                        keyword.line);
            }
        }

        resolveTypes(schemaName.text, state);
        return new Schema(schemaName.text, unescape(version.text), state.messages, state.enums);
    }

    private MessageDescriptor parseMessage(Lexer lexer, ParseState state,
                                           String schemaName, String parentFullName) {
        Token nameToken = lexer.expect(TokenType.IDENT);
        String fullName = parentFullName == null
                ? schemaName + "." + nameToken.text
                : parentFullName + "." + nameToken.text;
        if (state.usedFullNames.put(fullName, nameToken.line) != null) {
            throw new SchemaException("duplicate type definition '" + fullName + "'", nameToken.line);
        }
        lexer.expect('{');

        List<FieldBuild> fieldBuilds = new ArrayList<>();
        List<OneofBuild> oneofBuilds = new ArrayList<>();
        List<MessageDescriptor> nestedMessages = new ArrayList<>();
        List<EnumDescriptor> nestedEnums = new ArrayList<>();
        List<int[]> reservedRanges = new ArrayList<>();
        List<String> reservedNames = new ArrayList<>();
        Set<String> usedOneofNames = new HashSet<>();

        while (true) {
            Token token = lexer.peek();
            if (token.type == TokenType.CHAR && (Character) token.value == '}') {
                lexer.next();
                break;
            }
            if (token.type == TokenType.EOF) {
                throw new SchemaException("unexpected end of input, expected '}'", token.line);
            }
            if (token.type != TokenType.IDENT) {
                throw new SchemaException("unexpected token '" + token.text + "'", token.line);
            }
            if (token.text.equals("reserved")) {
                parseReserved(lexer, reservedRanges, reservedNames);
            } else if (token.text.equals("oneof")) {
                parseOneof(lexer, state, fieldBuilds, oneofBuilds, usedOneofNames, fullName);
            } else if (token.text.equals("message")) {
                lexer.next();
                nestedMessages.add(parseMessage(lexer, state, schemaName, fullName));
            } else if (token.text.equals("enum")) {
                lexer.next();
                nestedEnums.add(parseEnum(lexer, state, schemaName, fullName));
            } else {
                fieldBuilds.add(parseField(lexer, state, fullName, null));
            }
        }

        validateMessageBody(fullName, fieldBuilds, reservedRanges, reservedNames);

        List<FieldDescriptor> fields = new ArrayList<>();
        for (FieldBuild build : fieldBuilds) {
            fields.add(build.field);
        }
        List<OneofDescriptor> oneofs = new ArrayList<>();
        for (OneofBuild build : oneofBuilds) {
            List<FieldDescriptor> members = new ArrayList<>();
            for (FieldBuild memberBuild : build.members) {
                members.add(memberBuild.field);
            }
            oneofs.add(new OneofDescriptor(build.name, build.line, members));
        }
        return new MessageDescriptor(nameToken.text, fullName, nameToken.line,
                fields, oneofs, nestedMessages, nestedEnums, reservedRanges, reservedNames);
    }

    private void parseReserved(Lexer lexer, List<int[]> reservedRanges, List<String> reservedNames) {
        lexer.next();
        boolean nameReserved = lexer.peek().type == TokenType.STRING;
        while (true) {
            Token token = lexer.next();
            if (nameReserved) {
                if (token.type != TokenType.STRING) {
                    throw new SchemaException("reserved name must be a string literal", token.line);
                }
                reservedNames.add(unescape(token.text));
            } else {
                int from = parseFieldNumber(token);
                int to = from;
                if (lexer.consumeKeyword("to")) {
                    Token endToken = lexer.next();
                    if (endToken.type == TokenType.IDENT && endToken.text.equals("max")) {
                        to = CodecLimits.MAX_FIELD_NUMBER;
                    } else {
                        to = parseFieldNumber(endToken);
                    }
                    if (to < from) {
                        throw new SchemaException("reserved range end before start", endToken.line);
                    }
                }
                reservedRanges.add(new int[]{from, to});
            }
            if (lexer.consume(',')) {
                continue;
            }
            lexer.expect(';');
            return;
        }
    }

    private void parseOneof(Lexer lexer, ParseState state, List<FieldBuild> fieldBuilds,
                            List<OneofBuild> oneofBuilds, Set<String> usedOneofNames,
                            String scopeFullName) {
        lexer.next();
        Token nameToken = lexer.expect(TokenType.IDENT);
        if (!usedOneofNames.add(nameToken.text)) {
            throw new SchemaException("duplicate oneof '" + nameToken.text + "'", nameToken.line);
        }
        OneofBuild oneofBuild = new OneofBuild(nameToken.text, nameToken.line);
        lexer.expect('{');
        while (true) {
            Token token = lexer.peek();
            if (token.type == TokenType.CHAR && (Character) token.value == '}') {
                lexer.next();
                break;
            }
            if (token.type == TokenType.EOF) {
                throw new SchemaException("unexpected end of input in oneof block", token.line);
            }
            if (token.text.equals("repeated") || token.text.equals("map")
                    || token.text.equals("oneof") || token.text.equals("reserved")
                    || token.text.equals("message") || token.text.equals("enum")) {
                throw new SchemaException("oneof members must be singular non-map fields", token.line);
            }
            FieldBuild member = parseField(lexer, state, scopeFullName, nameToken.text);
            fieldBuilds.add(member);
            oneofBuild.members.add(member);
        }
        if (oneofBuild.members.isEmpty()) {
            throw new SchemaException("oneof '" + nameToken.text + "' must contain at least one field",
                    nameToken.line);
        }
        oneofBuilds.add(oneofBuild);
    }

    private FieldBuild parseField(Lexer lexer, ParseState state, String scopeFullName, String oneofName) {
        FieldLabel label = FieldLabel.OPTIONAL;
        ScalarType mapKeyType = null;
        Token first = lexer.peek();
        if (first.type == TokenType.IDENT && first.text.equals("repeated")) {
            lexer.next();
            label = FieldLabel.REPEATED;
        } else if (first.type == TokenType.IDENT && first.text.equals("map")) {
            if (oneofName != null) {
                throw new SchemaException("map fields are not allowed inside oneof", first.line);
            }
            lexer.next();
            label = FieldLabel.MAP;
            lexer.expect('<');
            mapKeyType = parseMapKeyType(lexer.expect(TokenType.IDENT));
            lexer.expect(',');
        }

        Token typeToken = lexer.expect(TokenType.IDENT);
        if (label == FieldLabel.MAP) {
            lexer.expect('>');
        }
        ScalarType scalarType = parseScalar(typeToken.text);
        if (label == FieldLabel.MAP && scalarType != null
                && (scalarType == ScalarType.BYTES || scalarType == ScalarType.FLOAT
                || scalarType == ScalarType.DOUBLE)) {
            throw new SchemaException("map value type '" + typeToken.text + "' is not allowed",
                    typeToken.line);
        }

        Token nameToken = lexer.expect(TokenType.IDENT);
        lexer.expect('=');
        int number = parseFieldNumber(lexer.expect(TokenType.INT));
        lexer.expect(';');

        FieldDescriptor field = new FieldDescriptor(nameToken.text, number, nameToken.line,
                label, scalarType, scalarType == null ? typeToken.text : null,
                mapKeyType, oneofName != null, oneofName);
        FieldBuild build = new FieldBuild(field, scopeFullName);
        state.fieldBuilds.add(build);
        return build;
    }

    private EnumDescriptor parseEnum(Lexer lexer, ParseState state,
                                     String schemaName, String parentFullName) {
        Token nameToken = lexer.expect(TokenType.IDENT);
        String fullName = parentFullName == null
                ? schemaName + "." + nameToken.text
                : parentFullName + "." + nameToken.text;
        if (state.usedFullNames.put(fullName, nameToken.line) != null) {
            throw new SchemaException("duplicate type definition '" + fullName + "'", nameToken.line);
        }
        lexer.expect('{');
        List<EnumDescriptor.Value> values = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        Set<Integer> usedNumbers = new HashSet<>();
        while (true) {
            Token token = lexer.peek();
            if (token.type == TokenType.CHAR && (Character) token.value == '}') {
                lexer.next();
                break;
            }
            if (token.type == TokenType.EOF) {
                throw new SchemaException("unexpected end of input, expected '}'", token.line);
            }
            Token valueName = lexer.expect(TokenType.IDENT);
            if (!usedNames.add(valueName.text)) {
                throw new SchemaException("duplicate enum value '" + valueName.text
                        + "' in enum " + fullName, valueName.line);
            }
            lexer.expect('=');
            int number = parseEnumNumber(lexer.expect(TokenType.INT));
            if (!usedNumbers.add(number)) {
                throw new SchemaException("duplicate enum number " + number
                        + " in enum " + fullName, valueName.line);
            }
            lexer.expect(';');
            values.add(new EnumDescriptor.Value(valueName.text, number, valueName.line));
        }
        if (values.isEmpty()) {
            throw new SchemaException("enum '" + fullName + "' must contain at least one value",
                    nameToken.line);
        }
        return new EnumDescriptor(nameToken.text, fullName, values);
    }

    private void validateMessageBody(String fullName, List<FieldBuild> fieldBuilds,
                                     List<int[]> reservedRanges, List<String> reservedNames) {
        Set<String> usedFieldNames = new HashSet<>();
        Set<Integer> usedFieldNumbers = new HashSet<>();
        for (FieldBuild build : fieldBuilds) {
            FieldDescriptor field = build.field;
            if (!usedFieldNames.add(field.name())) {
                throw new SchemaException("duplicate field name '" + field.name()
                        + "' in message " + fullName, field.declaredLine());
            }
            if (!usedFieldNumbers.add(field.number())) {
                throw new SchemaException("duplicate field number " + field.number()
                        + " in message " + fullName, field.declaredLine());
            }
            for (int[] range : reservedRanges) {
                if (field.number() >= range[0] && field.number() <= range[1]) {
                    throw new SchemaException("field '" + field.name() + "' reuses reserved number "
                            + field.number() + " in message " + fullName, field.declaredLine());
                }
            }
            if (reservedNames.contains(field.name())) {
                throw new SchemaException("field '" + field.name()
                        + "' reuses a reserved field name in message " + fullName, field.declaredLine());
            }
        }
    }

    private void resolveTypes(String schemaName, ParseState state) {
        Schema schema = new Schema(schemaName, "", state.messages, state.enums);
        for (FieldBuild build : state.fieldBuilds) {
            FieldDescriptor field = build.field;
            if (field.scalarType() != null) {
                continue;
            }
            String reference = field.typeName();
            EnumDescriptor resolvedEnum = null;
            MessageDescriptor resolvedMessage = null;
            String scope = build.scopeFullName;
            while (true) {
                String candidate = scope == null ? reference : scope + "." + reference;
                resolvedMessage = schema.findMessage(candidate);
                resolvedEnum = schema.findEnum(candidate);
                if (resolvedMessage != null || resolvedEnum != null || scope == null) {
                    break;
                }
                int dot = scope.lastIndexOf('.');
                scope = dot < 0 ? null : scope.substring(0, dot);
            }
            if (resolvedMessage == null && resolvedEnum == null) {
                throw new SchemaException("field '" + field.name() + "' references unknown type '"
                        + reference + "'", field.declaredLine());
            }
            field.resolveTypes(resolvedEnum, resolvedMessage);
        }
    }

    private ScalarType parseMapKeyType(Token token) {
        ScalarType type = parseScalar(token.text);
        if (type == null || type == ScalarType.FLOAT || type == ScalarType.DOUBLE
                || type == ScalarType.BYTES) {
            throw new SchemaException("invalid map key type '" + token.text + "'", token.line);
        }
        return type;
    }

    private ScalarType parseScalar(String text) {
        return switch (text) {
            case "int32" -> ScalarType.INT32;
            case "int64" -> ScalarType.INT64;
            case "bool" -> ScalarType.BOOL;
            case "float" -> ScalarType.FLOAT;
            case "double" -> ScalarType.DOUBLE;
            case "bytes" -> ScalarType.BYTES;
            case "string" -> ScalarType.STRING;
            default -> null;
        };
    }

    private int parseFieldNumber(Token token) {
        long value = parseNonNegativeInt(token);
        if (value < 1 || value > CodecLimits.MAX_FIELD_NUMBER) {
            throw new SchemaException("field number " + value + " out of allowed range 1.."
                    + CodecLimits.MAX_FIELD_NUMBER, token.line);
        }
        return (int) value;
    }

    private int parseEnumNumber(Token token) {
        long value = parseSignedInt(token);
        if (value < Integer.MIN_VALUE || value > 0xFFFFFFFFL) {
            throw new SchemaException("enum number " + value + " out of allowed range", token.line);
        }
        return (int) value;
    }

    private long parseNonNegativeInt(Token token) {
        if (token.type != TokenType.INT) {
            throw new SchemaException("expected integer, got '" + token.text + "'", token.line);
        }
        String text = token.text;
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new SchemaException("integer value '" + text + "' is out of range", token.line);
        }
    }

    private long parseSignedInt(Token token) {
        if (token.type != TokenType.INT) {
            throw new SchemaException("expected integer, got '" + token.text + "'", token.line);
        }
        String text = token.text;
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new SchemaException("integer value '" + text + "' is out of range", token.line);
        }
    }

    private static String unescape(String quoted) {
        String raw = quoted.substring(1, quoted.length() - 1);
        StringBuilder builder = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(++i);
                switch (next) {
                    case 'n' -> builder.append('\n');
                    case 't' -> builder.append('\t');
                    case 'r' -> builder.append('\r');
                    case '\\' -> builder.append('\\');
                    case '"' -> builder.append('"');
                    default -> throw new SchemaException("invalid escape sequence \\" + next, 1);
                }
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private static final class FieldBuild {
        private final FieldDescriptor field;
        private final String scopeFullName;

        private FieldBuild(FieldDescriptor field, String scopeFullName) {
            this.field = field;
            this.scopeFullName = scopeFullName;
        }
    }

    private static final class OneofBuild {
        private final String name;
        private final int line;
        private final List<FieldBuild> members = new ArrayList<>();

        private OneofBuild(String name, int line) {
            this.name = name;
            this.line = line;
        }
    }

    private static final class ParseState {
        private final List<MessageDescriptor> messages = new ArrayList<>();
        private final List<EnumDescriptor> enums = new ArrayList<>();
        private final List<FieldBuild> fieldBuilds = new ArrayList<>();
        private final Map<String, Integer> usedFullNames = new LinkedHashMap<>();
    }

    private enum TokenType {
        IDENT,
        INT,
        STRING,
        CHAR,
        EOF
    }

    private static final class Token {
        private final TokenType type;
        private final String text;
        private final Object value;
        private final int line;

        private Token(TokenType type, String text, Object value, int line) {
            this.type = type;
            this.text = text;
            this.value = value;
            this.line = line;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private static final class Lexer {
        private final String input;
        private int pos;
        private int line = 1;

        private Lexer(String input) {
            this.input = input;
        }

        private Token peek() {
            int savedPos = pos;
            int savedLine = line;
            Token token = next();
            pos = savedPos;
            line = savedLine;
            return token;
        }

        private Token next() {
            skipWhitespaceAndComments();
            if (pos >= input.length()) {
                return new Token(TokenType.EOF, "", null, line);
            }
            char c = input.charAt(pos);
            if (Character.isLetter(c) || c == '_') {
                int start = pos;
                while (pos < input.length()) {
                    char ch = input.charAt(pos);
                    if (Character.isLetterOrDigit(ch) || ch == '_') {
                        pos++;
                    } else {
                        break;
                    }
                }
                return new Token(TokenType.IDENT, input.substring(start, pos), null, line);
            }
            if (Character.isDigit(c) || c == '-') {
                int start = pos;
                if (c == '-') {
                    pos++;
                }
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
                return new Token(TokenType.INT, input.substring(start, pos), null, line);
            }
            if (c == '"') {
                return readString();
            }
            pos++;
            return new Token(TokenType.CHAR, String.valueOf(c), c, line);
        }

        private Token readString() {
            int startLine = line;
            int start = pos;
            pos++;
            while (pos < input.length()) {
                char ch = input.charAt(pos);
                if (ch == '\\' && pos + 1 < input.length()) {
                    if (input.charAt(pos + 1) == '\n') {
                        line++;
                    }
                    pos += 2;
                    continue;
                }
                if (ch == '"') {
                    pos++;
                    return new Token(TokenType.STRING, input.substring(start, pos), null, startLine);
                }
                if (ch == '\n') {
                    line++;
                }
                pos++;
            }
            throw new SchemaException("unterminated string literal", startLine);
        }

        private void skipWhitespaceAndComments() {
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '\n') {
                    line++;
                    pos++;
                } else if (Character.isWhitespace(c)) {
                    pos++;
                } else if (c == '/' && pos + 1 < input.length() && input.charAt(pos + 1) == '/') {
                    pos += 2;
                    while (pos < input.length() && input.charAt(pos) != '\n') {
                        pos++;
                    }
                } else if (c == '/' && pos + 1 < input.length() && input.charAt(pos + 1) == '*') {
                    int commentStart = line;
                    pos += 2;
                    while (pos + 1 < input.length()
                            && !(input.charAt(pos) == '*' && input.charAt(pos + 1) == '/')) {
                        if (input.charAt(pos) == '\n') {
                            line++;
                        }
                        pos++;
                    }
                    if (pos + 1 >= input.length()) {
                        throw new SchemaException("unterminated block comment", commentStart);
                    }
                    pos += 2;
                } else {
                    return;
                }
            }
        }

        private Token expect(TokenType type) {
            Token token = next();
            if (token.type != type) {
                throw new SchemaException("expected " + type + " but got '" + token.text + "'",
                        token.line);
            }
            return token;
        }

        private void expect(char c) {
            Token token = next();
            if (token.type != TokenType.CHAR || (Character) token.value != c) {
                throw new SchemaException("expected '" + c + "' but got '" + token.text + "'",
                        token.line);
            }
        }

        private void expectKeyword(String keyword) {
            Token token = expect(TokenType.IDENT);
            if (!token.text.equals(keyword)) {
                throw new SchemaException("expected keyword '" + keyword + "' but got '"
                        + token.text + "'", token.line);
            }
        }

        private boolean consume(char c) {
            Token token = peek();
            if (token.type == TokenType.CHAR && (Character) token.value == c) {
                next();
                return true;
            }
            return false;
        }

        private boolean consumeKeyword(String keyword) {
            Token token = peek();
            if (token.type == TokenType.IDENT && token.text.equals(keyword)) {
                next();
                return true;
            }
            return false;
        }
    }
}
