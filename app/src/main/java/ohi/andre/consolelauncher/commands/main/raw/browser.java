package ohi.andre.consolelauncher.commands.main.raw;

import ohi.andre.consolelauncher.browser.AuraBrowserActivity;
import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.commands.CommandAbstraction;
import ohi.andre.consolelauncher.commands.ExecutePack;
import ohi.andre.consolelauncher.commands.main.MainPack;

public class browser implements CommandAbstraction {

    @Override
    public String exec(ExecutePack pack) {
        MainPack info = (MainPack) pack;

        String url = null;
        try {
            url = pack.getString();
        } catch (Exception ignored) {}

        if (url != null && !url.trim().isEmpty()
                && !url.startsWith("http://")
                && !url.startsWith("https://")
                && url.contains(".") && !url.contains(" ")) {
            url = "https://" + url;
        }

        AuraBrowserActivity.open(info.context, url);

        return info.res.getString(R.string.output_openingbrowser,
                url != null ? url : "home");
    }

    @Override public int[] argType() { return new int[0]; }
    @Override public int priority() { return 3; }
    @Override public int helpRes() { return R.string.help_browser; }
    @Override public String onArgNotFound(ExecutePack pack, int index) { return null; }
    @Override public String onNotArgEnough(ExecutePack pack, int nArgs) {
        MainPack info = (MainPack) pack;
        AuraBrowserActivity.open(info.context, null);
        return info.res.getString(R.string.output_openingbrowser, "home");
    }
}