package vaddempudi.gnanesh.syntaxcli.ime;

public final class BasicKey {

    public static final int CODE_NONE       = 0;
    public static final int CODE_SHIFT      = -1;
    public static final int CODE_DELETE     = -2;
    public static final int CODE_ENTER      = -3;
    public static final int CODE_SPACE      = -4;
    public static final int CODE_SYMBOLS    = -5;
    public static final int CODE_SYMBOLS_2  = -6;
    public static final int CODE_LEFT       = -7;
    public static final int CODE_RIGHT      = -8;
    public static final int CODE_SYM_PAGE_1 = -9;
    public static final int CODE_SYM_PAGE_2 = -10;
    public static final int CODE_SYM_PAGE_3 = -11;
    public static final int CODE_SYM_PAGE_4 = -12;
    public static final int CODE_ABC        = -13;

    public final int code;
    public final String lower;
    public final String upper;
    public final String label;
    public final float weight;

    public BasicKey(int code, String lower, String upper, String label, float weight) {
        this.code = code;
        this.lower = lower;
        this.upper = upper;
        this.label = label;
        this.weight = weight;
    }

    public String displayLabel(boolean shifted) {
        if (label != null) return label;
        if (shifted && upper != null) return upper;
        return lower;
    }

    public String commitText(boolean shifted) {
        if (code != CODE_NONE) return null;
        if (shifted && upper != null) return upper;
        return lower;
    }
}