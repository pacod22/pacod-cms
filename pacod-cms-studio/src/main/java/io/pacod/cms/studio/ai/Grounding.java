package io.pacod.cms.studio.ai;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a writing-help answer states that its material -- the passage, the brief, the article
 * around the cursor, the body laid out -- does not: its numbers and names that no piece of the
 * material has, and its pull quotes that do not repeat a text word for word. Listed for the
 * author to check, never refused: the operator reviews every answer, and both halves are
 * heuristics.
 *
 * <ul>
 *   <li><b>A number</b> is a run of digits, with {@code . , : / '} and the spaces between groups;
 *       it is the material's when its digits are one of the material's numbers or digit groups,
 *       or each of its groups is one -- {@code 1,200} is {@code 1 200} is {@code 1200}, {@code 09:00}
 *       is {@code 9:00}, {@code 12.05.2026} is {@code 2026-05-12}.</li>
 *   <li><b>A name</b> is a capitalised word that is not the first of a sentence, not in a heading
 *       written in title case, and not written in lower case elsewhere in the answer; it is the
 *       material's when a word there is the same ignoring case, or shares its first four letters
 *       and differs by at most three more, so an inflected or derived form counts.</li>
 * </ul>
 *
 * <p>What is not checked: anything in {@code [square brackets]}, the placeholders a draft leaves
 * for the author; names in a language that capitalises every noun ({@link #CAPITALISES_NOUNS}), or
 * where a piece of the material is in another script than the answer -- an English brief for a
 * Bulgarian article transliterates every name. Numbers written as words, a name at the start of a
 * sentence and a claim made without either pass unseen.
 */
final class Grounding {

    /** The most an answer lists: enough to send the author looking, short enough to read. */
    static final int MAX_LISTED = 12;
    /** The words of a pull quote shown when it is listed. */
    private static final int QUOTE_WORDS = 8;
    /** Languages that capitalise every noun, where a capital letter says nothing about a name. */
    static final Set<String> CAPITALISES_NOUNS = Set.of("de", "lb");

    private static final String NUMBER = "\\p{Nd}+(?:[.,:/'\\u2019\\u00A0\\u202F\\u2009 -]\\p{Nd}+)*";
    private static final String WORD = "[\\p{L}\\p{M}]+";
    private static final Pattern WORDS = Pattern.compile(WORD);
    /** An answer's tokens: a placeholder (skipped), a number, a word. */
    private static final Pattern TOKEN = Pattern.compile(
            "(?<placeholder>\\[[^\\[\\]]*\\])|(?<number>" + NUMBER + ")|(?<word>" + WORD + ")");
    /** The material's: every number and word, placeholders included. */
    private static final Pattern FACT = Pattern.compile("(?<number>" + NUMBER + ")|(?<word>" + WORD + ")");
    private static final Pattern DIGITS = Pattern.compile("\\p{Nd}+");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?\\u2026\\u3002\\uFF01\\uFF1F]");
    private static final Pattern HEADING = Pattern.compile("h[1-6]");
    private static final String PULLQUOTE = "blockquote.body-pullquote";

    private Grounding() {
    }

    /**
     * What {@code answer} -- one or more pieces of HTML, a draft's title, description and body --
     * states that no piece of {@code material} has, in order of appearance, each once, at most
     * {@value #MAX_LISTED}: numbers and names as written, a pull quote as its opening words.
     */
    static List<String> unverified(String language, List<String> material, String... answer) {
        Known known = new Known();
        List<Element> bodies = new ArrayList<>();
        StringBuilder answerText = new StringBuilder();
        for (String piece : answer) {
            Element body = parse(piece);
            bodies.add(body);
            answerText.append(body.text()).append('\n');
        }
        Character.UnicodeScript script = script(answerText);
        boolean names = !CAPITALISES_NOUNS.contains(language == null ? "" : language.toLowerCase(Locale.ROOT));
        for (String piece : material) {
            Element body = parse(piece);
            known.add(body);
            Character.UnicodeScript own = script(body.text());
            if (own != null && own != script) {
                names = false;
            }
        }

        List<Token> tokens = new ArrayList<>();
        for (Element body : bodies) {
            for (Segment segment : segments(body)) {
                tokens.addAll(tokens(segment));
            }
        }
        Set<String> lowerInAnswer = new HashSet<>();
        for (Token token : tokens) {
            if (token.word() && Character.isLowerCase(token.text().codePointAt(0))) {
                lowerInAnswer.add(lower(token.text()));
            }
        }

        Map<String, String> listed = new LinkedHashMap<>();
        for (Token token : tokens) {
            if (!token.word()) {
                if (!known.hasNumber(token.text())) {
                    listed.putIfAbsent("n:" + whole(token.text()), token.text());
                }
            } else if (names && token.name() && !lowerInAnswer.contains(lower(token.text()))
                    && !known.hasWord(lower(token.text()))) {
                listed.putIfAbsent("w:" + lower(token.text()), token.text());
            }
        }
        List<String> rest = new ArrayList<>();
        for (Element body : bodies) {
            Element copy = body.clone();
            copy.select(PULLQUOTE).remove();
            rest.addAll(words(copy.text()));
        }
        for (Element body : bodies) {
            for (Element quote : body.select(PULLQUOTE)) {
                List<String> words = words(quote.text());
                if (!words.isEmpty() && Collections.indexOfSubList(known.sequence, words) < 0
                        && Collections.indexOfSubList(rest, words) < 0) {
                    listed.putIfAbsent("q:" + String.join(" ", words), opening(quote.text()));
                }
            }
        }
        return listed.values().stream().limit(MAX_LISTED).toList();
    }

    /** A run of text inside one block, or an attribute's; {@code heading} when that block is a heading. */
    private record Segment(String text, boolean heading) {
    }

    /** A number or a word; {@code name} for a capitalised word that could only be one. */
    private record Token(String text, boolean word, boolean name) {
    }

    private static Element parse(String html) {
        return Jsoup.parseBodyFragment(html == null ? "" : html).body();
    }

    /**
     * The body's text cut at every block and line break, so that the first word of a paragraph,
     * a list item or a heading starts a sentence; then the {@code alt} and {@code title}
     * attributes, which are published too.
     */
    private static List<Segment> segments(Element body) {
        List<Segment> segments = new ArrayList<>();
        List<Segment> attributes = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        boolean[] heading = {false};
        Runnable flush = () -> {
            if (!text.toString().isBlank()) {
                segments.add(new Segment(text.toString(), heading[0]));
            }
            text.setLength(0);
        };
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode textNode) {
                    if (text.isEmpty()) {
                        heading[0] = inHeading(textNode);
                    }
                    text.append(textNode.text());
                } else if (node instanceof Element element) {
                    if (element.isBlock() || element.normalName().equals("br")) {
                        flush.run();
                    }
                    for (String name : List.of("alt", "title")) {
                        if (!element.attr(name).isBlank()) {
                            attributes.add(new Segment(element.attr(name), false));
                        }
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element element && element.isBlock()) {
                    flush.run();
                }
            }
        }, body);
        flush.run();
        segments.addAll(attributes);
        return segments;
    }

    private static boolean inHeading(TextNode text) {
        for (Element parent = text.parent() instanceof Element e ? e : null; parent != null; parent = parent.parent()) {
            if (parent.isBlock()) {
                return HEADING.matcher(parent.normalName()).matches();
            }
        }
        return false;
    }

    /**
     * The segment's numbers and words. A word is a {@code name} when it is capitalised, two letters
     * or more, not the first of its sentence and not in a title-case heading.
     */
    private static List<Token> tokens(Segment segment) {
        boolean titleCase = segment.heading() && titleCase(segment.text());
        List<Token> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(segment.text());
        boolean start = true;
        int end = 0;
        while (matcher.find()) {
            if (SENTENCE_END.matcher(segment.text().substring(end, matcher.start())).find()) {
                start = true;
            }
            end = matcher.end();
            if (matcher.group("placeholder") != null) {
                start = false;
                continue;
            }
            String text = matcher.group();
            boolean word = matcher.group("word") != null;
            boolean name = word && !start && !titleCase && capitalised(text) && text.codePointCount(0, text.length()) >= 2;
            tokens.add(new Token(text, word, name));
            start = false;
        }
        return tokens;
    }

    /** "Getting There", "Key Facts": two words or more, and every one of four letters or more capitalised. */
    private static boolean titleCase(String text) {
        Matcher matcher = WORDS.matcher(text);
        int words = 0;
        int longWords = 0;
        while (matcher.find()) {
            words++;
            String word = matcher.group();
            if (word.codePointCount(0, word.length()) >= 4) {
                longWords++;
                if (!capitalised(word)) {
                    return false;
                }
            }
        }
        return words >= 2 && longWords > 0;
    }

    private static boolean capitalised(String word) {
        int first = word.codePointAt(0);
        return Character.isUpperCase(first) || Character.isTitleCase(first);
    }

    /** The script most of the text's letters are written in, or null without letters. */
    private static Character.UnicodeScript script(CharSequence text) {
        Map<Character.UnicodeScript, Integer> counts = new HashMap<>();
        text.codePoints().filter(Character::isLetter).forEach(cp -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script != Character.UnicodeScript.COMMON && script != Character.UnicodeScript.INHERITED
                    && script != Character.UnicodeScript.UNKNOWN) {
                counts.merge(script, 1, Integer::sum);
            }
        });
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /** Words for comparing runs: lower case, split on everything that is neither letter nor digit. */
    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String word : lower(text).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    private static String opening(String text) {
        String[] words = text.strip().split("\\s+");
        String opening = String.join(" ", Arrays.copyOf(words, Math.min(words.length, QUOTE_WORDS)));
        return "\u201C" + opening + (words.length > QUOTE_WORDS ? "\u2026" : "") + "\u201D";
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    /** A number's digits, separators gone, without leading zeros. */
    private static String whole(String number) {
        StringBuilder digits = new StringBuilder();
        Matcher matcher = DIGITS.matcher(number);
        while (matcher.find()) {
            digits.append(matcher.group());
        }
        return withoutLeadingZeros(digits.toString());
    }

    private static List<String> groups(String number) {
        List<String> groups = new ArrayList<>();
        Matcher matcher = DIGITS.matcher(number);
        while (matcher.find()) {
            groups.add(withoutLeadingZeros(matcher.group()));
        }
        return groups;
    }

    private static String withoutLeadingZeros(String digits) {
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0') {
            i++;
        }
        return digits.substring(i);
    }

    /** The material's numbers and words, and its words in order for the pull quotes. */
    private static final class Known {
        private final Set<String> wholes = new HashSet<>();
        private final Set<String> groups = new HashSet<>();
        private final Set<String> words = new HashSet<>();
        private final Map<String, List<String>> byPrefix = new HashMap<>();
        private final List<String> sequence = new ArrayList<>();

        void add(Element body) {
            for (Segment segment : segments(body)) {
                Matcher matcher = FACT.matcher(segment.text());
                while (matcher.find()) {
                    if (matcher.group("number") != null) {
                        wholes.add(whole(matcher.group()));
                        groups.addAll(Grounding.groups(matcher.group()));
                    } else {
                        String word = lower(matcher.group());
                        if (words.add(word) && word.length() >= 4) {
                            byPrefix.computeIfAbsent(word.substring(0, 4), k -> new ArrayList<>()).add(word);
                        }
                    }
                }
            }
            sequence.addAll(Grounding.words(body.text()));
        }

        boolean hasNumber(String number) {
            String whole = whole(number);
            return wholes.contains(whole) || groups.contains(whole) || groups.containsAll(Grounding.groups(number));
        }

        /** The same word ignoring case, or one sharing its first four letters and differing by at most three more. */
        boolean hasWord(String word) {
            if (words.contains(word)) {
                return true;
            }
            if (word.length() < 4) {
                return false;
            }
            for (String known : byPrefix.getOrDefault(word.substring(0, 4), List.of())) {
                int common = 0;
                while (common < Math.min(word.length(), known.length()) && word.charAt(common) == known.charAt(common)) {
                    common++;
                }
                if (Math.max(word.length(), known.length()) - common <= 3) {
                    return true;
                }
            }
            return false;
        }
    }
}
