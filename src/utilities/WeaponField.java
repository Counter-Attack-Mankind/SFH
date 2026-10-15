import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The confirmed mapping between a configuration field name and one {@code addGun(...)} parameter.
 *
 * <p>Every entry was read off the real {@code Stats_Guns.addGun} body
 * (decompiled/scripts/Stats_Guns.as:1522-1574), not guessed from parameter names. The {@code
 * assignment} text below is the literal assignment performed by that method.</p>
 *
 * <p>Fields whose parameter is not stored verbatim (a runtime transform or a second consumer
 * exists) are listed in {@link #refusedFields()} and are rejected by name, so a configuration can
 * never silently change an attribute through a transformed parameter.</p>
 */
public final class WeaponField {

    /** Declared ActionScript type of the target parameter in {@code addGun}. */
    public enum Type {
        /** {@code int} or {@code uint}: only integral values, and {@code uint} requires {@code >= 0}. */
        INTEGER,
        /** {@code Number}: integral or fractional values. */
        NUMBER
    }

    public final String name;
    /** 1-based {@code addGun} parameter index. */
    public final int param;
    /** Field on the created gun object that receives this parameter. */
    public final String field;
    /** Literal assignment in {@code Stats_Guns.addGun}. */
    public final String assignment;
    public final Type type;
    /** True when the ActionScript type is {@code uint}; negative values are then illegal. */
    public final boolean unsigned;
    public final double min;
    public final double max;

    private WeaponField(String name, int param, String field, String assignment, Type type,
                        boolean unsigned, double min, double max) {
        this.name = name;
        this.param = param;
        this.field = field;
        this.assignment = assignment;
        this.type = type;
        this.unsigned = unsigned;
        this.min = min;
        this.max = max;
    }

    private static final Map<String, WeaponField> SUPPORTED = new LinkedHashMap<>();

    private static void add(String name, int param, String field, String assignment, Type type,
                            boolean unsigned, double min, double max) {
        SUPPORTED.put(name, new WeaponField(name, param, field, assignment, type, unsigned, min, max));
    }

    static {
        // -- first batch required by the specification -------------------------------
        add("damage", 8, "dmg", "dmg = param8", Type.NUMBER, false, 0, 1000000);
        add("shootDelay", 18, "shootDelay", "shootDelay = param18", Type.NUMBER, false, 0, 3600);
        add("clipSize", 13, "clipSize", "clipSize = param13", Type.INTEGER, true, 0, 1000000);
        add("recoil", 16, "recoil", "recoil = param16", Type.NUMBER, false, -1000, 1000);
        // -- additional plain numeric passthrough parameters -------------------------
        add("range", 15, "range", "range = param15", Type.INTEGER, true, 0, 1000000);
        add("force", 9, "force", "force = param9", Type.NUMBER, false, -1000000, 1000000);
        add("clipSpare", 14, "clipSpare", "clipSpare = param14", Type.INTEGER, true, 0, 1000000);
        add("splash", 11, "splash", "splash = param11", Type.NUMBER, false, 0, 1000000);
        add("splashMult", 12, "splashMult", "splashMult = param12", Type.NUMBER, false, -1000, 1000);
        add("xOff", 19, "xOff", "xOff = param19", Type.INTEGER, true, 0, 100000);
        add("yOff", 20, "yOff", "yOff = param20", Type.INTEGER, false, -100000, 100000);
    }

    private static final Map<String, String> REFUSED = new LinkedHashMap<>();

    static {
        REFUSED.put("bodBreak",
                "param10 is stored as bodBreak = param10 * 0.2, so the raw parameter is not the runtime value");
        REFUSED.put("lvlReq",
                "param7 is stored as lvlReq = param7 and also passed to Stats_Classes.getItemCost(param7), "
                        + "which sets the weapon price; the two effects cannot be separated from this call site");
        REFUSED.put("autoFire",
                "param17 is a Boolean, not a numeric attribute");
        REFUSED.put("cost", "cost is derived from param7 via Stats_Classes.getItemCost and is not an addGun parameter");
    }

    public static WeaponField byName(String name) {
        return name == null ? null : SUPPORTED.get(name);
    }

    public static List<WeaponField> all() {
        return Collections.unmodifiableList(new ArrayList<>(SUPPORTED.values()));
    }

    public static Map<String, String> refusedFields() {
        return Collections.unmodifiableMap(REFUSED);
    }

    public String describeRange() {
        String lower = min == Math.rint(min) ? Long.toString((long) min) : Double.toString(min);
        String upper = max == Math.rint(max) ? Long.toString((long) max) : Double.toString(max);
        return lower + ".." + upper;
    }

    /** Validates a configured value for this field; returns null when acceptable. */
    public String validate(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "value is not a finite number";
        }
        if (value < min || value > max) {
            return "value " + value + " is outside the legal range " + describeRange();
        }
        if (type == Type.INTEGER) {
            if (value != Math.rint(value)) {
                return "field '" + name + "' maps to the integer parameter param" + param
                        + " and requires an integral value, got " + value;
            }
            if (unsigned && value < 0) {
                return "field '" + name + "' maps to the unsigned parameter param" + param
                        + " and cannot be negative, got " + value;
            }
        }
        return null;
    }

    /** True when the configured value must be encoded as a floating point constant. */
    public boolean needsDouble(double value) {
        return value != Math.rint(value) || Math.abs(value) > 4294967295.0;
    }
}
