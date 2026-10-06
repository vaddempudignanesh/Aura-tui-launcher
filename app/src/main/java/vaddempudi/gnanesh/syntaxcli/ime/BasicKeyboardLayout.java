package vaddempudi.gnanesh.syntaxcli.ime;

import java.util.ArrayList;
import java.util.List;

public final class BasicKeyboardLayout {

    public final List<BasicKey[]> letterRows;
    public final List<BasicKey[]> symbolPage1;
    public final List<BasicKey[]> symbolPage2;
    public final List<BasicKey[]> symbolPage3;

    public BasicKeyboardLayout() {
        letterRows = new ArrayList<>();
        symbolPage1 = new ArrayList<>();
        symbolPage2 = new ArrayList<>();
        symbolPage3 = new ArrayList<>();
        buildLetters();
        buildSymbolPage1();
        buildSymbolPage2();
        buildSymbolPage3();
    }

    private void buildLetters() {
        letterRows.add(new BasicKey[] {
                sym("1"), sym("2"), sym("3"), sym("4"), sym("5"),
                sym("6"), sym("7"), sym("8"), sym("9"), sym("0")
        });
        letterRows.add(new BasicKey[] {
                key("q"), key("w"), key("e"), key("r"), key("t"),
                key("y"), key("u"), key("i"), key("o"), key("p")
        });
        letterRows.add(new BasicKey[] {
                key("a"), key("s"), key("d"), key("f"), key("g"),
                key("h"), key("j"), key("k"), key("l")
        });
        letterRows.add(new BasicKey[] {
                new BasicKey(BasicKey.CODE_SHIFT, null, null, "\u21E7", 1.5f),
                key("z"), key("x"), key("c"), key("v"),
                key("b"), key("n"), key("m"),
                new BasicKey(BasicKey.CODE_DELETE, null, null, "\u232B", 1.5f)
        });
        letterRows.add(new BasicKey[] {
                new BasicKey(BasicKey.CODE_SYMBOLS, null, null, "?123", 1.5f),
                new BasicKey(BasicKey.CODE_LEFT, null, null, "\u25C0", 1f),
                new BasicKey(BasicKey.CODE_SPACE, null, null, "space", 4f),
                new BasicKey(BasicKey.CODE_RIGHT, null, null, "\u25B6", 1f),
                new BasicKey(BasicKey.CODE_ENTER, null, null, "\u23CE", 1.5f)
        });
    }

    private void buildSymbolPage1() {
        symbolPage1.add(new BasicKey[] {
                sym("1"), sym("2"), sym("3"), sym("4"), sym("5"),
                sym("6"), sym("7"), sym("8"), sym("9"), sym("0")
        });
        symbolPage1.add(new BasicKey[] {
                sym("-"), sym("/"), sym(":"), sym(";"), sym("("),
                sym(")"), sym("$"), sym("&"), sym("@"), sym("\"")
        });
        symbolPage1.add(new BasicKey[] {
                sym("."), sym(","), sym("?"), sym("!"), sym("'"),
                sym("*"), sym("#"), sym("%"), sym("+"), sym("=")
        });
        symbolPage1.add(new BasicKey[] {
                new BasicKey(BasicKey.CODE_SYM_PAGE_2, null, null, "=\\<", 1.5f),
                new BasicKey(BasicKey.CODE_DELETE, null, null, "\u232B", 1.5f),
                new BasicKey(BasicKey.CODE_SPACE, null, null, "space", 4f),
                new BasicKey(BasicKey.CODE_ABC, null, null, "ABC", 1.5f),
                new BasicKey(BasicKey.CODE_ENTER, null, null, "\u23CE", 1.5f)
        });
    }

    private void buildSymbolPage2() {
        symbolPage2.add(new BasicKey[] {
                sym("~"), sym("`"), sym("|"), sym("\u2022"), sym("\u221A"),
                sym("\u03C0"), sym("\u00F7"), sym("\u00D7"), sym("\u00B6"), sym("\u2206")
        });
        symbolPage2.add(new BasicKey[] {
                sym("\u00A3"), sym("\u00A2"), sym("\u20AC"), sym("\u00A5"), sym("^"),
                sym("\u00B0"), sym("{"), sym("}"), sym("["), sym("]")
        });
        symbolPage2.add(new BasicKey[] {
                sym("\u00A9"), sym("\u00AE"), sym("\u00A7"), sym("\u00B5"), sym("<"),
                sym(">"), sym("\u00AB"), sym("\u00BB"), sym("\u00BF"), sym("\u00A1")
        });
        symbolPage2.add(new BasicKey[] {
                new BasicKey(BasicKey.CODE_SYM_PAGE_3, null, null, "2/3", 1.5f),
                new BasicKey(BasicKey.CODE_DELETE, null, null, "\u232B", 1.5f),
                new BasicKey(BasicKey.CODE_SPACE, null, null, "space", 4f),
                new BasicKey(BasicKey.CODE_ABC, null, null, "ABC", 1.5f),
                new BasicKey(BasicKey.CODE_ENTER, null, null, "\u23CE", 1.5f)
        });
    }

    private void buildSymbolPage3() {
        symbolPage3.add(new BasicKey[] {
                sym("\u2190"), sym("\u2191"), sym("\u2192"), sym("\u2193"),
                sym("\u2194"), sym("\u2195"), sym("\u21A9"), sym("\u21AA"),
                sym("\u21BB"), sym("\u21BA")
        });
        symbolPage3.add(new BasicKey[] {
                sym("\u2605"), sym("\u2606"), sym("\u2665"), sym("\u2661"),
                sym("\u266A"), sym("\u266B"), sym("\u2660"), sym("\u2663"),
                sym("\u2666"), sym("\u2664")
        });
        symbolPage3.add(new BasicKey[] {
                sym("\u2022"), sym("\u25CF"), sym("\u25CB"), sym("\u25A0"),
                sym("\u25A1"), sym("\u25B2"), sym("\u25BC"), sym("\u25B6"),
                sym("\u25C0"), sym("\u2B50")
        });
        symbolPage3.add(new BasicKey[] {
                new BasicKey(BasicKey.CODE_SYM_PAGE_1, null, null, "1/3", 1.5f),
                new BasicKey(BasicKey.CODE_DELETE, null, null, "\u232B", 1.5f),
                new BasicKey(BasicKey.CODE_SPACE, null, null, "space", 4f),
                new BasicKey(BasicKey.CODE_ABC, null, null, "ABC", 1.5f),
                new BasicKey(BasicKey.CODE_ENTER, null, null, "\u23CE", 1.5f)
        });
    }

    private static BasicKey key(String letter) {
        String upper = letter.toUpperCase(java.util.Locale.US);
        return new BasicKey(BasicKey.CODE_NONE, letter, upper, null, 1f);
    }

    private static BasicKey sym(String s) {
        return new BasicKey(BasicKey.CODE_NONE, s, s, null, 1f);
    }
}