import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects duplicate member names in a JSON document.
 *
 * <p>The JSON reader used by this project keeps the last value of a duplicated member and therefore
 * cannot itself report the collision. Duplicate keys are exactly how a configuration could silently
 * override a weapon change, so the raw text is scanned before parsing and any repeated member inside
 * the same object is reported with its JSON path.</p>
 */
public final class JsonLint {

    private static final class Frame {
        final boolean object;
        final String path;
        final Set<String> keys = new HashSet<>();
        int index;
        String currentKey;

        Frame(boolean object, String path) {
            this.object = object;
            this.path = path;
        }
    }

    private JsonLint() {
    }

    /** Returns one description per duplicated member; empty when the document is clean. */
    public static List<String> findDuplicateMembers(String text) {
        List<String> duplicates = new ArrayList<>();
        Deque<Frame> stack = new ArrayDeque<>();
        int index = 0;
        int length = text.length();
        while (index < length) {
            char c = text.charAt(index);
            if (c == '"') {
                StringBuilder out = new StringBuilder();
                int next = readString(text, index, out);
                if (next < 0) {
                    break;
                }
                index = next;
                if (!stack.isEmpty() && stack.peek().object && nextSignificant(text, index) == ':') {
                    Frame frame = stack.peek();
                    String key = out.toString();
                    if (!frame.keys.add(key)) {
                        duplicates.add(frame.path + "." + key);
                    }
                    frame.currentKey = key;
                }
                continue;
            }
            if (c == '{' || c == '[') {
                stack.push(new Frame(c == '{', childPath(stack)));
                index++;
                continue;
            }
            if (c == '}' || c == ']') {
                if (!stack.isEmpty()) {
                    stack.pop();
                }
                index++;
                continue;
            }
            if (c == ',') {
                if (!stack.isEmpty()) {
                    Frame frame = stack.peek();
                    if (frame.object) {
                        frame.currentKey = null;
                    } else {
                        frame.index++;
                    }
                }
                index++;
                continue;
            }
            index++;
        }
        return duplicates;
    }

    private static char nextSignificant(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c;
            }
        }
        return '\0';
    }

    private static String childPath(Deque<Frame> stack) {
        if (stack.isEmpty()) {
            return "$";
        }
        Frame parent = stack.peek();
        if (parent.object) {
            return parent.path + "." + (parent.currentKey == null ? "?" : parent.currentKey);
        }
        return parent.path + "[" + parent.index + "]";
    }

    /** Reads a JSON string starting at {@code start}; returns the index after it, or -1. */
    private static int readString(String text, int start, StringBuilder out) {
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                if (i + 1 >= text.length()) {
                    return -1;
                }
                char esc = text.charAt(i + 1);
                if (esc == 'u' && i + 5 < text.length()) {
                    out.append('?');
                    i += 6;
                } else {
                    out.append(esc);
                    i += 2;
                }
                continue;
            }
            if (c == '"') {
                return i + 1;
            }
            out.append(c);
            i++;
        }
        return -1;
    }
}
