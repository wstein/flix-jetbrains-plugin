package org.flixlang.intellij.lang;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;
import static com.intellij.psi.TokenType.*;
import static org.flixlang.intellij.lang.psi.FlixTypes.*;

/**
 * Hand-ported JFlex lexer for Flix, generated from a careful reading of the authoritative
 * Scala lexer at:
 *   - ca.uwaterloo.flix.language.ast.TokenKind     (flix/main/src/.../ast/TokenKind.scala)
 *   - ca.uwaterloo.flix.language.phase.Lexer       (flix/main/src/.../phase/Lexer.scala)
 *
 * See Flix.tokens.txt (in this same directory) for the full token reference this file
 * implements, including notes on a few narrow, explicitly-flagged simplifications (number
 * literal suffix-error combinations, the FreeDot error, unterminated-literal recovery at EOF,
 * and the leading-'$'-escaped-name span). Every other rule below is a faithful, literal port
 * of the corresponding logic in Lexer.scala.
 */
%%

%public
%class _FlixLexer
%implements FlexLexer
%unicode
%function advance
%type IElementType

%{
    /**
     * Remembers the start offset of a multi-chunk token (a nested block comment, or a string
     * literal segment) so several JFlex rule matches can be merged into a single logical token.
     * The trick: save {@code zzStartRead} when the chunk begins, keep matching/consuming
     * without returning a token, then restore {@code zzStartRead} to the saved value right
     * before the terminating rule returns. Since IntelliJ's FlexLexer contract reports
     * getTokenStart()/getTokenEnd() from zzStartRead/zzMarkedPos at the moment advance()
     * returns, this correctly widens the reported token to the whole merged span.
     */
    private int tokenStart;

    /** Nesting depth of the block comment currently being scanned (Flix block comments nest). */
    private int commentDepth = 0;

    /**
     * Nesting depth of "${ ... }" string-interpolation blocks. Each stack entry is the
     * brace-nesting depth *within* that particular interpolation's expression body (bumped by
     * ordinary '{'/'}' inside the expression, e.g. a record literal); an interpolation's own
     * closing '}' is the one seen while its entry is 0. A new "${" always pushes a fresh 0, so
     * arbitrarily nested interpolations (including a string-with-interpolation nested inside
     * another interpolation's expression body) are supported without extra lexer states -
     * the interior of "${ ... }" is just ordinary Flix code, so it is scanned by the very same
     * YYINITIAL rules as everything else.
     */
    private final java.util.ArrayDeque<Integer> interpDepth = new java.util.ArrayDeque<Integer>();

    /**
     * True while resuming a string literal's text right after a '}' that closed an
     * interpolation block, as opposed to resuming right after the string's opening quote.
     * Mirrors Lexer.scala's acceptStringInterpolation/acceptString interplay: the segment is
     * typed LITERAL_STRING_INTERPOLATION_R if it closes the string (reached a real closing
     * quote) having been resumed this way, and plain LITERAL_STRING if the string never had any
     * interpolation at all.
     */
    private boolean stringResumedAfterInterpolation = false;

    /**
     * Exclusive end offset of the most recently matched run of whitespace. Comparing this
     * against zzStartRead of the current token lets us test "is this token immediately
     * preceded by whitespace, with no gap" without needing true lexer lookbehind - used for the
     * FreeDot error condition and for the tight-vs-whitespace "->" distinction (Lexer.scala
     * lines 353-365).
     */
    private int lastWhitespaceEnd = -1;
%}

%eof{
    return;
%eof}

%xstate STRING
%xstate BLOCK_COMMENT

/* ==================== Macros ==================== */

WHITE_SPACE_CHAR = [ \t\f\r\n]
LETTER           = [a-zA-Z]
DIGIT            = [0-9]
DIGITS           = {DIGIT}+ ("_" {DIGIT}+)*
HEX_DIGIT        = [0-9a-fA-F]
HEX_DIGITS       = {HEX_DIGIT}+ ("_" {HEX_DIGIT}+)*

/* `\D([.]\D)?(e([+]|[-])?\D([.]\D)?)?` from Lexer.scala's acceptNumber, minus the "no fractional
 * or exponent part" case (that shape is plain LITERAL_INT, handled by a separate rule). */
EXP_TAIL   = "e" [+\-]? {DIGITS} ("." {DIGITS})?
FLOAT_CORE = ({DIGITS} "." {DIGITS} ({EXP_TAIL})?) | ({DIGITS} {EXP_TAIL})

/* isNameChar in Lexer.scala: letter, digit, '_', '!', or '$'. */
ID_TAIL = [a-zA-Z0-9_!$]

/* A leading "_"? makes this macro cover both a plain name and Lexer.scala's `case '_' => ...`
 * branch (an underscore immediately followed by a name/math/operator char), which the Scala
 * lexer folds into the very same NameLowercase/NameUppercase/NameMath/GenericOperator kinds. */
LOWER_NAME = "_"? [a-z] {ID_TAIL}*
UPPER_NAME = "_"? [A-Z] {ID_TAIL}*

/* isMathNameChar: unicode range U+2200-U+22FF. */
MATH_CHAR = [∀-⋿]
MATH_NAME = "_"? {MATH_CHAR}+

/* isUserOp charset from Lexer.scala. */
USEROP_CHAR = [+\-*<>=!&|\^$]
GENERIC_OP  = "_"? {USEROP_CHAR}+

/* acceptEscapedName: "$" + name, forced to NameLowercase regardless of the case of what
 * follows. Simplification (documented in Flix.tokens.txt): the real lexer excludes the leading
 * '$' from the token's source span (via resetStart()); this port keeps it in the matched text. */
ESCAPED_NAME = "$" {LETTER} {ID_TAIL}*

/* consumeSingleEscapes: a backslash followed by any single character (including another
 * backslash, a quote, or a newline) is always consumed as one escaped unit. */
ESCAPE = "\\" [^]

%%

/* ==================================================================================
 * YYINITIAL: ordinary Flix source code. Also reused, unmodified, to scan the interior
 * expression of a "${ ... }" string interpolation block (see interpDepth above) - the Scala
 * lexer does the same thing (acceptStringInterpolation calls the very same scanToken/lex loop).
 * ================================================================================== */

<YYINITIAL> {

    /* ---- whitespace ---- */
    {WHITE_SPACE_CHAR}+ { lastWhitespaceEnd = zzMarkedPos; return WHITE_SPACE; }

    /* ---- comments ----
     * A doc comment is EXACTLY three leading slashes; two, or four-or-more, is a line comment.
     * (acceptLineOrDocComment: slashCount == 1 *additional* slash beyond the two already
     * consumed by scanToken means three total.) */
    "//" "/"* [^\n]* {
        int slashes = 0;
        while (slashes < yylength() && yycharat(slashes) == '/') slashes++;
        return slashes == 3 ? COMMENT_DOC : COMMENT_LINE;
    }
    "/*" { commentDepth = 1; tokenStart = zzStartRead; yybegin(BLOCK_COMMENT); }

    /* ---- prefixed-hash collection literals (also part of the `Keywords` PrefixTree) ---- */
    "Array#"  { return ARRAY_HASH; }
    "List#"   { return LIST_HASH; }
    "Map#"    { return MAP_HASH; }
    "Set#"    { return SET_HASH; }
    "Vector#" { return VECTOR_HASH; }

    /* ---- keywords (the `Keywords` PrefixTree in Lexer.scala) ----
     * No explicit "not followed by a name-char" guard is needed: JFlex's longest-match means a
     * longer identifier (e.g. "definitely") always wins over a keyword prefix (e.g. "def")
     * automatically, exactly mirroring the PrefixTree's tailCondition. */
    "Static"          { return STATIC_UPPER_KW; }
    "Univ"            { return UNIV_KW; }
    "alias"           { return ALIAS_KW; }
    "and"             { return AND_KW; }
    "as"              { return AS_KW; }
    "case"            { return CASE_KW; }
    "catch"           { return CATCH_KW; }
    "checked_cast"    { return CHECKED_CAST_KW; }
    "checked_ecast"   { return CHECKED_ECAST_KW; }
    "choose*"         { return CHOOSE_STAR_KW; }
    "choose"          { return CHOOSE_KW; }
    "def"             { return DEF_KW; }
    "discard"         { return DISCARD_KW; }
    "eff"             { return EFF_KW; }
    "else"            { return ELSE_KW; }
    "ematch"          { return EMATCH_KW; }
    "enum"            { return ENUM_KW; }
    "false"           { return FALSE_KW; }
    "fix"             { return FIX_KW; }
    "forA"            { return FORA_KW; }
    "forM"            { return FORM_KW; }
    "forall"          { return FORALL_KW; }
    "force"           { return FORCE_KW; }
    "foreach"         { return FOREACH_KW; }
    "from"            { return FROM_KW; }
    "handler"         { return HANDLER_KW; }
    "if"              { return IF_KW; }
    "import"          { return IMPORT_KW; }
    "inject"          { return INJECT_KW; }
    "instance"        { return INSTANCE_KW; }
    "instanceof"      { return INSTANCEOF_KW; }
    "into"            { return INTO_KW; }
    "law"             { return LAW_KW; }
    "lawful"          { return LAWFUL_KW; }
    "lazy"            { return LAZY_KW; }
    "let"             { return LET_KW; }
    "match"           { return MATCH_KW; }
    "mod"             { return MOD_KW; }
    "mut"             { return MUT_KW; }
    "new"             { return NEW_KW; }
    "not"             { return NOT_KW; }
    "null"            { return NULL_KW; }
    "open_variant_as" { return OPEN_VARIANT_AS_KW; }
    "open_variant"    { return OPEN_VARIANT_KW; }
    "or"              { return OR_KW; }
    "par"             { return PAR_KW; }
    "pquery"          { return PQUERY_KW; }
    "project"         { return PROJECT_KW; }
    "psolve"          { return PSOLVE_KW; }
    "pub"             { return PUB_KW; }
    "query"           { return QUERY_KW; }
    "redef"           { return REDEF_KW; }
    "region"          { return REGION_KW; }
    "restrictable"    { return RESTRICTABLE_KW; }
    "run"             { return RUN_KW; }
    "rvadd"           { return RVADD_KW; }
    "rvand"           { return RVAND_KW; }
    "rvnot"           { return RVNOT_KW; }
    "rvsub"           { return RVSUB_KW; }
    "sealed"          { return SEALED_KW; }
    "select"          { return SELECT_KW; }
    "solve"           { return SOLVE_KW; }
    "spawn"           { return SPAWN_KW; }
    "static"          { return STATIC_KW; }
    "struct"          { return STRUCT_KW; }
    "super"           { return SUPER_KW; }
    "throw"           { return THROW_KW; }
    "trait"           { return TRAIT_KW; }
    "true"            { return TRUE_KW; }
    "try"             { return TRY_KW; }
    "type"            { return TYPE_KW; }
    "unchecked_cast"  { return UNCHECKED_CAST_KW; }
    "unsafe"          { return UNSAFE_KW; }
    "use"             { return USE_KW; }
    "where"           { return WHERE_KW; }
    "with"            { return WITH_KW; }
    "xor"             { return XOR_KW; }
    "xvar"            { return XVAR_KW; }
    "yield"           { return YIELD_KW; }

    /* ---- simple tokens (the `SimpleTokens` PrefixTree - consumed unconditionally) ---- */
    "#("  { return HASH_PAREN_L; }
    "#{"  { return HASH_CURLY_L; }
    "#|"  { return HASH_BAR; }
    "#"   { return HASH; }
    "("   { return PAREN_L; }
    ")"   { return PAREN_R; }
    ","   { return COMMA; }
    ";"   { return SEMI; }
    "???" { return HOLE_ANONYMOUS; }
    "["   { return BRACKET_L; }
    "\\"  { return BACKSLASH; }
    "]"   { return BRACKET_R; }
    "`"   { return TICK; }
    "|#"  { return BAR_HASH; }
    "~"   { return TILDE; }

    /* '{'/'}' need to track string-interpolation brace nesting (see interpDepth above), so they
     * are not plain fixed-literal rules like the other delimiters. */
    "{" {
        if (!interpDepth.isEmpty()) {
            interpDepth.push(interpDepth.pop() + 1);
        }
        return CURLY_L;
    }
    "}" {
        if (!interpDepth.isEmpty()) {
            int depth = interpDepth.pop();
            if (depth > 0) {
                interpDepth.push(depth - 1);
                return CURLY_R;
            }
            // This '}' closes a "${ ... }" interpolation block at nesting depth 0: resume
            // scanning the surrounding string literal's text, starting at this '}'. Falling
            // through here without a `return` continues the scan loop in the STRING state.
            tokenStart = zzStartRead;
            stringResumedAfterInterpolation = true;
            yybegin(STRING);
        } else {
            return CURLY_R;
        }
    }

    /* ---- operators (the `Operators` PrefixTree) ----
     * As with keywords, no explicit "not followed by a user-op char" guard is needed: any
     * longer run of user-op characters (e.g. "!==", "<*>") is matched by the GENERIC_OP catch-all
     * further down and, being longer, automatically wins - exactly mirroring the PrefixTree's
     * tailCondition = !isUserOp(next char). Where a fixed operator here ties in length with what
     * GENERIC_OP would also match (e.g. plain "!="), this rule wins because it is listed first. */
    "!="  { return BANG_EQUAL; }
    "!"   { return BANG; }
    "&"   { return AMPERSAND; }
    "*"   { return STAR; }
    "+"   { return PLUS; }
    "-"   { return MINUS; }
    ":::" { return COLON_COLON_COLON; }
    "::"  { return COLON_COLON; }
    ":-"  { return COLON_MINUS; }
    ":"   { return COLON; }
    "<+>" { return ANGLED_PLUS; }
    "<-"  { return ARROW_THIN_L; }
    "<=>" { return ANGLED_EQUAL; }
    "<="  { return ANGLE_L_EQUAL; }
    "<"   { return ANGLE_L; }
    "=="  { return EQUAL_EQUAL; }
    "=>"  { return ARROW_THICK_R; }
    "="   { return EQUAL; }
    ">="  { return ANGLE_R_EQUAL; }
    ">"   { return ANGLE_R; }
    "^"   { return CARET; }
    "|"   { return BAR; }

    /* Slash is a manual special case in Lexer.scala's scanToken (checked before line and block
     * comments, which are matched above with higher priority since they're longer matches). */
    "/" { return SLASH; }

    /* ---- "->" tight vs. whitespace disambiguation (Lexer.scala lines 353-365) ----
     * Both spellings are the literal text "->"; there is no separate "~>" token anywhere in
     * TokenKind.scala/Lexer.scala (see the correction note at the top of Flix.tokens.txt).
     * Whitespace on EITHER side selects the whitespace variant; a run of 3+ user-op characters
     * (e.g. "->>") is longer and is instead matched by GENERIC_OP below. */
    "->" / {WHITE_SPACE_CHAR} { return ARROW_THIN_R; }
    "->" { return (zzStartRead == lastWhitespaceEnd) ? ARROW_THIN_R : ARROW_THIN_R_TIGHT; }

    /* ---- dot / dot-whitespace / FreeDot ----
     * A '.' immediately preceded by whitespace is LexerError.FreeDot in the reference lexer;
     * this port folds that error case into BAD_CHARACTER (see the note on TokenKind.Err in
     * Flix.tokens.txt) rather than a dedicated DOT-shaped error token. */
    "." / {WHITE_SPACE_CHAR} { return (zzStartRead == lastWhitespaceEnd) ? BAD_CHARACTER : DOT_WHITESPACE; }
    "." { return (zzStartRead == lastWhitespaceEnd) ? BAD_CHARACTER : DOT; }

    /* ---- annotation / at ---- */
    "@" {LETTER}+ { return ANNOTATION; }
    "@"           { return AT; }

    /* ---- bare '$' (must be listed before GENERIC_OP: both match a lone "$" at length 1, and
     * this rule must win that tie - see isUserOp catch-all discussion in Flix.tokens.txt). ---- */
    "$" { return DOLLAR; }

    /* ---- built-in (e.g. "%%MY_BUILT_IN%%"); isBuiltInChar = uppercase letter, digit, or '_'. */
    "%%" [A-Z0-9_]* "%%" { return BUILT_IN; }

    /* ---- debug string interpolator: the bare letter 'd' immediately before an opening '"'.
     * Only the 'd' itself is consumed here; the following '"' is picked up fresh by the string
     * rule right below, exactly like Lexer.scala's `case 'd' if peekIs('"') => DebugInterpolator`. */
    "d" / "\"" { return DEBUG_INTERPOLATOR; }

    /* ---- regex literal: "regex" immediately followed by an opening quote, then content up to
     * an unescaped closing quote. Unlike plain strings, newlines are allowed in regex content
     * (Lexer.scala's acceptRegex never checks for '\n'), and there is no interpolation. */
    "regex\"" ( {ESCAPE} | [^\"\\] )* "\"" { return LITERAL_REGEX; }

    /* ---- unterminated regex literal: no closing quote reachable before EOF. Without this, the
     * strict rule above simply fails to match (it requires a real closing quote), so JFlex falls
     * through character-by-character -- "regex" alone is a valid NAME_LOWERCASE identifier, so it
     * gets lexed and later *parsed* as an ordinary name expression before the broken remainder
     * fragments the tree deeply, unlike char/string literals (which happen to collapse cleanly by
     * incidence, not by any deliberate matching rule -- see Flix.tokens.txt's own "not fully
     * modeled in this port" note on LITERAL_STRING). This rule only ever fires as JFlex's
     * longest-match fallback when the strict rule above already failed to match anything, since a
     * real closing quote always makes the strict rule's match longer. Matches Lexer.scala folding
     * every unterminated-literal case into one error token spanning the whole malformed lexeme. */
    "regex\"" ( {ESCAPE} | [^\"\\] )* { return BAD_CHARACTER; }

    /* ---- char literal: content up to an unescaped closing quote. Like acceptRegex (and unlike
     * acceptString), acceptChar never special-cases '\n', so newlines are allowed here too. ---- */
    "'" ( {ESCAPE} | [^'\\] )* "'" { return LITERAL_CHAR; }

    /* ---- string literal (with "${ ... }" interpolation support) ----
     * A string cannot be scanned as one flat regex because whether a bare '$' starts an
     * interpolation depends on whether it is followed by '{', and both '$' and '{' can also
     * appear as perfectly ordinary literal characters - a case that is not expressible as a
     * single non-nested JFlex rule (see the long-form discussion in Flix.tokens.txt / project
     * notes). This is handled with a dedicated STRING state instead, one bounded chunk at a
     * time, merged into a single logical token via the zzStartRead trick described above -
     * this exactly reproduces Lexer.scala's token boundaries (LITERAL_STRING /
     * LITERAL_STRING_INTERPOLATION_L / LITERAL_STRING_INTERPOLATION_R), it is just implemented
     * with an explicit lexer state instead of Lexer.scala's recursive acceptStringInterpolation. */
    "\"" { tokenStart = zzStartRead; stringResumedAfterInterpolation = false; yybegin(STRING); }

    /* ---- holes ---- */
    "?" {LETTER} {ID_TAIL}* { return HOLE_NAMED; }
    {LOWER_NAME} "?"        { return HOLE_VARIABLE; }
    {UPPER_NAME} "?"        { return HOLE_VARIABLE; }
    {ESCAPED_NAME} "?"      { return HOLE_VARIABLE; }

    /* ---- names ---- */
    {ESCAPED_NAME} { return NAME_LOWERCASE; }
    {LOWER_NAME}   { return NAME_LOWERCASE; }
    {UPPER_NAME}   { return NAME_UPPERCASE; }
    {MATH_NAME}    { return NAME_MATH; }

    /* ---- number literals ----
     * Decimal grammar: `\D([.]\D)?(e([+]|[-])?\D([.]\D)?)?(i8|i16|i32|i64|ii|f32|f64|ff)?`.
     * Hex grammar (int suffixes only, no float form): `0x\h+(_\h+)*(i8|i16|i32|i64|ii)?`.
     * A "float-shaped" literal (has '.' or 'e') combined with an integer suffix is a lexer
     * error in Lexer.scala (IntegerSuffixOnFloat) that is not specially modeled here: such
     * input simply matches the un-suffixed LITERAL_FLOAT rule, leaving the suffix text to be
     * re-lexed as a separate NAME_LOWERCASE token (see the NUMBER LITERALS note in
     * Flix.tokens.txt). */
    "0x" {HEX_DIGITS} "i8"  { return LITERAL_INT8; }
    "0x" {HEX_DIGITS} "i16" { return LITERAL_INT16; }
    "0x" {HEX_DIGITS} "i32" { return LITERAL_INT32; }
    "0x" {HEX_DIGITS} "i64" { return LITERAL_INT64; }
    "0x" {HEX_DIGITS} "ii"  { return LITERAL_BIGINT; }
    "0x" {HEX_DIGITS}       { return LITERAL_INT; }

    {DIGITS} "i8"  { return LITERAL_INT8; }
    {DIGITS} "i16" { return LITERAL_INT16; }
    {DIGITS} "i32" { return LITERAL_INT32; }
    {DIGITS} "i64" { return LITERAL_INT64; }
    {DIGITS} "ii"  { return LITERAL_BIGINT; }
    {DIGITS}       { return LITERAL_INT; }

    {FLOAT_CORE} "f32" { return LITERAL_FLOAT32; }
    {FLOAT_CORE} "f64" { return LITERAL_FLOAT64; }
    {FLOAT_CORE} "ff"  { return LITERAL_BIGDECIMAL; }
    {FLOAT_CORE}       { return LITERAL_FLOAT; }

    /* A float suffix does not require a fractional or exponent part: acceptNumber sets
     * `mustBeFloat` only on '.'/'e', but accepts f32/f64/ff regardless, so `0ff`, `123ff`,
     * `1f64` and `-20938f32` are ordinary float literals. Without these the digits lex as
     * LITERAL_INT and the suffix re-lexes as a name. JFlex takes the longest match, so these
     * win over the bare {DIGITS} rule above wherever a suffix is present. */
    {DIGITS} "f32" { return LITERAL_FLOAT32; }
    {DIGITS} "f64" { return LITERAL_FLOAT64; }
    {DIGITS} "ff"  { return LITERAL_BIGDECIMAL; }

    /* ---- user-defined ("generic") operators and underscore ---- */
    {GENERIC_OP} { return GENERIC_OPERATOR; }
    "_"          { return UNDERSCORE; }
}

/* ==================================================================================
 * STRING: resuming a string literal's literal text, either right after the opening '"' or
 * right after a '}' that closed a "${ ... }" interpolation block (tokenStart/
 * stringResumedAfterInterpolation are set by whichever of those two triggered entry to this
 * state - see YYINITIAL above). Every rule here consumes one bounded, unambiguous chunk; none
 * of them return a token except the two that end the current segment, so that all the
 * intervening chunks merge into a single logical token via the zzStartRead trick.
 * ================================================================================== */

<STRING> {
    {ESCAPE} { /* escaped character: keep accumulating */ }

    "${" {
        zzStartRead = tokenStart;
        interpDepth.push(0);
        yybegin(YYINITIAL);
        return LITERAL_STRING_INTERPOLATION_L;
    }

    "\"" {
        zzStartRead = tokenStart;
        yybegin(YYINITIAL);
        return stringResumedAfterInterpolation ? LITERAL_STRING_INTERPOLATION_R : LITERAL_STRING;
    }

    /* A run of ordinary characters, or a lone '$' not immediately followed by '{' (the '$' and
     * the run-of-plain-chars rules are deliberately disjoint - see the discussion in
     * Flix.tokens.txt on why a bare "$" cannot simply be folded into the plain-char class). */
    [^\"\\$\n]+ { /* plain content: keep accumulating */ }
    "$"         { /* plain content: keep accumulating */ }

    /* Unterminated string: Lexer.scala treats a raw newline before the closing quote as an
     * error (LexerError.UnterminatedString), which this port folds into BAD_CHARACTER. */
    \n {
        zzStartRead = tokenStart;
        yybegin(YYINITIAL);
        return BAD_CHARACTER;
    }
    <<EOF>> {
        zzStartRead = tokenStart;
        yybegin(YYINITIAL);
        return BAD_CHARACTER;
    }
}

/* ==================================================================================
 * BLOCK_COMMENT: Flix's block comments (which nest; acceptBlockComment tracks a level counter).
 * All chunks merge into one COMMENT_BLOCK token via the same zzStartRead trick as STRING.
 * ================================================================================== */

<BLOCK_COMMENT> {
    "/*" { commentDepth++; }
    "*/" {
        commentDepth--;
        if (commentDepth == 0) {
            zzStartRead = tokenStart;
            yybegin(YYINITIAL);
            return COMMENT_BLOCK;
        }
    }
    [^/*]+ { /* plain comment text: keep accumulating */ }
    "/"    { /* a lone slash, not part of an open or close comment marker: keep accumulating */ }
    "*"    { /* a lone star, not part of an open or close comment marker: keep accumulating */ }

    <<EOF>> {
        // Unterminated block comment (LexerError.UnterminatedBlockComment in the reference
        // lexer), folded into BAD_CHARACTER as with the other unterminated-literal cases above.
        zzStartRead = tokenStart;
        yybegin(YYINITIAL);
        return BAD_CHARACTER;
    }
}

/* Anything not matched by any rule above in any state. */
[^] { return BAD_CHARACTER; }
