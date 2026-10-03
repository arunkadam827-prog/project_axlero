package logstream_backend.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses the LogStream query language into a tenant-scoped Lucene
 * {@link Query}.
 *
 * <p>
 * Supported syntax (case-insensitive keywords):
 * </p>
 * 
 * <pre>
 *   level:ERROR
 *   service:billing-api
 *   response_time > 1000
 *   response_time:1000 TO 2000
 *   level:ERROR AND service:billing-api AND response_time > 1000
 *   (level:ERROR OR level:WARN) AND NOT service:auth
 *   "database timeout"
 * </pre>
 *
 * <p>
 * <strong>Security invariant:</strong> {@link #parse(String, String)} always
 * AND-s a {@code FILTER} clause on {@code tenant_id}. There is no code path
 * that
 * returns a query without the tenant scope, so request input can never escape
 * its tenant — see {@code docs/MULTI_TENANCY.md} §4.
 * </p>
 */
@Component
public class LogQueryParser {

    private static final Set<String> STRING_FIELDS = Set.of(
            LogFields.LEVEL,
            LogFields.SERVICE,
            LogFields.HOST,
            LogFields.TRACE_ID,
            LogFields.ERROR_CODE);

    private static final Set<String> RANGE_FIELDS = Set.of(
            LogFields.RESPONSE_TIME,
            LogFields.EPOCH_MILLIS);

    private final Analyzer analyzer;

    public LogQueryParser(LuceneIndexManager indexManager) {
        this.analyzer = indexManager.analyzer();
    }

    /**
     * Parses {@code expression} and returns a query that can only ever match
     * documents belonging to {@code tenant}.
     */
    public Query parse(String expression, String tenant) {

        Query inner = (expression == null || expression.isBlank())
                ? new MatchAllDocsQuery()
                : parseExpression(expression);

        BooleanQuery.Builder root = new BooleanQuery.Builder();
        root.add(inner, BooleanClause.Occur.MUST);
        root.add(tenantClause(tenant), BooleanClause.Occur.FILTER);
        return root.build();
    }

    /** Query for a tenant with no additional constraints. */
    public Query tenantOnly(String tenant) {
        return parse(null, tenant);
    }

    /** Adds an extra level filter on top of a parsed query. */
    public Query parseWithFilters(
            String expression,
            String tenant,
            String level,
            String service) {

        BooleanQuery.Builder root = new BooleanQuery.Builder();
        root.add(parse(expression, tenant), BooleanClause.Occur.MUST);

        if (level != null && !level.isBlank() && !"ALL".equalsIgnoreCase(level)) {
            root.add(
                    term(LogFields.LEVEL, LogFields.canonicalLevel(level)),
                    BooleanClause.Occur.FILTER);
        }
        if (service != null && !service.isBlank() && !"ALL".equalsIgnoreCase(service)) {
            root.add(term(LogFields.SERVICE, service), BooleanClause.Occur.FILTER);
        }
        return root.build();
    }

    private BooleanQuery tenantClause(String tenant) {
        return new BooleanQuery.Builder()
                .add(term(LogFields.TENANT_ID, tenant), BooleanClause.Occur.FILTER)
                .build();
    }

    // ------------------------------------------------------------------
    // Recursive-descent parser
    // ------------------------------------------------------------------

    private Query parseExpression(String input) {
        List<Token> tokens = new Lexer(input).tokenize();
        Parser parser = new Parser(tokens);
        Query query = parser.parseOr();
        if (!parser.isAtEnd()) {
            throw new IllegalArgumentException(
                    "Unexpected token '" + parser.peek().text + "' in query");
        }
        return query;
    }

    private Query term(String field, String value) {
        return new TermQuery(new Term(field, value));
    }

    private Query stringFieldQuery(String field, String value) {
        String normalized = LogFields.LEVEL.equals(field)
                ? LogFields.canonicalLevel(value)
                : value;
        return term(field, normalized);
    }

    private Query messageQuery(String text) {
        try {
            QueryParser parser = new QueryParser(LogFields.MESSAGE, analyzer);
            return parser.parse(QueryParser.escape(text));
        } catch (Exception e) {
            // Fall back to a literal term if the parser chokes.
            return term(LogFields.MESSAGE, text.toLowerCase(Locale.ROOT));
        }
    }

    private Query rangeQuery(String field, String low, String high) {
        long l = parseLong(low, field);
        long h = parseLong(high, field);
        if (LogFields.EPOCH_MILLIS.equals(field)) {
            return LongPoint.newRangeQuery(field, Math.min(l, h), Math.max(l, h));
        }
        return IntPoint.newRangeQuery(
                field, (int) Math.min(l, h), (int) Math.max(l, h));
    }

    private Query comparison(String field, Op op, String value) {
        long v = parseLong(value, field);
        if (LogFields.EPOCH_MILLIS.equals(field)) {
            return switch (op) {
                case GT -> LongPoint.newRangeQuery(field, Math.addExact(v, 1), Long.MAX_VALUE);
                case GTE -> LongPoint.newRangeQuery(field, v, Long.MAX_VALUE);
                case LT -> LongPoint.newRangeQuery(field, Long.MIN_VALUE, Math.subtractExact(v, 1));
                case LTE -> LongPoint.newRangeQuery(field, Long.MIN_VALUE, v);
            };
        }
        int i = (int) v;
        return switch (op) {
            case GT -> IntPoint.newRangeQuery(field, i + 1, Integer.MAX_VALUE);
            case GTE -> IntPoint.newRangeQuery(field, i, Integer.MAX_VALUE);
            case LT -> IntPoint.newRangeQuery(field, Integer.MIN_VALUE, i - 1);
            case LTE -> IntPoint.newRangeQuery(field, Integer.MIN_VALUE, i);
        };
    }

    private static long parseLong(String raw, String field) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Field '" + field + "' expects a numeric value but got '" + raw + "'");
        }
    }

    // ------------------------------------------------------------------
    // Tokens
    // ------------------------------------------------------------------

    private enum Type {
        WORD, STRING, COLON, OP, LPAREN, RPAREN, AND, OR, NOT, TO, EOF
    }

    private enum Op {
        GT, GTE, LT, LTE;

        static Op from(String symbol) {
            return switch (symbol) {
                case ">" -> GT;
                case ">=" -> GTE;
                case "<" -> LT;
                case "<=" -> LTE;
                default -> throw new IllegalArgumentException("Unknown operator " + symbol);
            };
        }
    }

    private record Token(Type type, String text) {
    }

    /**
     * End-of-input sentinel. {@code peek()} returns this once the token stream
     * is exhausted; without a distinct type the implicit-AND loop in
     * {@code parseAnd()} mistook the end marker for another bare term and
     * recursed forever, building millions of BooleanQueries until the heap
     * was exhausted. The type must therefore never be WORD/STRING/LPAREN/NOT.
     */
    private static final Token EOF_TOKEN = new Token(Type.EOF, "<end>");

    private static final class Lexer {

        private final String input;
        private int pos;

        Lexer(String input) {
            this.input = input;
        }

        List<Token> tokenize() {
            List<Token> tokens = new ArrayList<>();
            while (pos < input.length()) {
                char c = input.charAt(pos);

                if (Character.isWhitespace(c)) {
                    pos++;
                    continue;
                }
                if (c == '(') {
                    tokens.add(new Token(Type.LPAREN, "("));
                    pos++;
                    continue;
                }
                if (c == ')') {
                    tokens.add(new Token(Type.RPAREN, ")"));
                    pos++;
                    continue;
                }
                if (c == ':') {
                    tokens.add(new Token(Type.COLON, ":"));
                    pos++;
                    continue;
                }
                if (c == '>' || c == '<') {
                    if (pos + 1 < input.length() && input.charAt(pos + 1) == '=') {
                        tokens.add(new Token(Type.OP, input.substring(pos, pos + 2)));
                        pos += 2;
                    } else {
                        tokens.add(new Token(Type.OP, String.valueOf(c)));
                        pos++;
                    }
                    continue;
                }
                if (c == '"' || c == '\'') {
                    tokens.add(readQuoted(c));
                    continue;
                }
                tokens.add(readWord());
            }
            return tokens;
        }

        private Token readQuoted(char quote) {
            pos++; // skip opening quote
            StringBuilder sb = new StringBuilder();
            while (pos < input.length() && input.charAt(pos) != quote) {
                sb.append(input.charAt(pos++));
            }
            if (pos < input.length()) {
                pos++; // skip closing quote
            }
            return new Token(Type.STRING, sb.toString());
        }

        private Token readWord() {
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (Character.isWhitespace(c) || c == '(' || c == ')' || c == ':'
                        || c == '>' || c == '<' || c == '"' || c == '\'') {
                    break;
                }
                sb.append(c);
                pos++;
            }
            String word = sb.toString();
            Type type = switch (word.toUpperCase(Locale.ROOT)) {
                case "AND" -> Type.AND;
                case "OR" -> Type.OR;
                case "NOT" -> Type.NOT;
                case "TO" -> Type.TO;
                default -> Type.WORD;
            };
            return new Token(type, word);
        }
    }

    private final class Parser {

        private final List<Token> tokens;
        private int index;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        Query parseOr() {
            Query left = parseAnd();
            while (match(Type.OR)) {
                Query right = parseAnd();
                BooleanQuery.Builder builder = new BooleanQuery.Builder();
                builder.add(left, BooleanClause.Occur.SHOULD);
                builder.add(right, BooleanClause.Occur.SHOULD);
                builder.setMinimumNumberShouldMatch(1);
                left = builder.build();
            }
            return left;
        }

        Query parseAnd() {
            Query left = parseNot();
            while (true) {
                if (match(Type.AND)) {
                    left = and(left, parseNot());
                } else if (peek().type() == Type.WORD
                        || peek().type() == Type.STRING
                        || peek().type() == Type.LPAREN
                        || peek().type() == Type.NOT) {
                    // Implicit AND between adjacent conditions.
                    left = and(left, parseNot());
                } else {
                    break;
                }
            }
            return left;
        }

        private Query and(Query left, Query right) {
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            builder.add(left, BooleanClause.Occur.MUST);
            builder.add(right, BooleanClause.Occur.MUST);
            return builder.build();
        }

        Query parseNot() {
            if (match(Type.NOT)) {
                Query inner = parseNot();
                BooleanQuery.Builder builder = new BooleanQuery.Builder();
                builder.add(inner, BooleanClause.Occur.MUST_NOT);
                builder.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
                return builder.build();
            }
            return parsePrimary();
        }

        Query parsePrimary() {
            if (match(Type.LPAREN)) {
                Query inner = parseOr();
                expect(Type.RPAREN);
                return inner;
            }
            return parseCondition();
        }

        Query parseCondition() {

            Token token = peek();

            if (token.type() == Type.STRING) {
                advance();
                return messageQuery(token.text());
            }

            if (token.type() != Type.WORD) {
                throw new IllegalArgumentException(
                        "Expected a condition but found '" + token.text() + "'");
            }

            advance();
            String fieldOrText = token.text();

            // field:value
            if (match(Type.COLON)) {
                return parseFieldValue(fieldOrText);
            }

            // field > value
            if (peek().type() == Type.OP && isRangeField(fieldOrText)) {
                Op op = Op.from(peek().text());
                advance();
                Token valueToken = expectValue();
                return comparison(fieldOrText, op, valueToken.text());
            }

            // Bare word -> full text search.
            return messageQuery(fieldOrText);
        }

        private Query parseFieldValue(String field) {

            Token value = expectValue();

            // range syntax: field:1000 TO 2000
            if (match(Type.TO)) {
                Token high = expectValue();
                if (!isRangeField(field)) {
                    throw new IllegalArgumentException(
                            "Range syntax is not supported for field '" + field + "'");
                }
                return rangeQuery(field, value.text(), high.text());
            }

            if (isRangeField(field)) {
                long v = parseLong(value.text(), field);
                if (LogFields.EPOCH_MILLIS.equals(field)) {
                    return LongPoint.newExactQuery(field, v);
                }
                return IntPoint.newExactQuery(field, (int) v);
            }

            if (STRING_FIELDS.contains(field)) {
                return stringFieldQuery(field, value.text());
            }

            if (LogFields.MESSAGE.equals(field)) {
                return messageQuery(value.text());
            }

            // Unknown field: treat "field:value" as free text on the message.
            return messageQuery(field + ":" + value.text());
        }

        private boolean isRangeField(String field) {
            return RANGE_FIELDS.contains(field);
        }

        private Token expectValue() {
            Token token = peek();
            if (token.type() != Type.WORD && token.type() != Type.STRING) {
                throw new IllegalArgumentException(
                        "Expected a value but found '" + token.text() + "'");
            }
            advance();
            return token;
        }

        private boolean match(Type type) {
            if (peek().type() == type) {
                advance();
                return true;
            }
            return false;
        }

        private void expect(Type type) {
            if (!match(type)) {
                throw new IllegalArgumentException(
                        "Expected " + type + " but found '" + peek().text() + "'");
            }
        }

        private Token peek() {
            return index < tokens.size()
                    ? tokens.get(index)
                    : EOF_TOKEN;
        }

        private void advance() {
            index++;
        }

        boolean isAtEnd() {
            return index >= tokens.size();
        }
    }
}
