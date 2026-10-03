package ohi.andre.consolelauncher.commands.main.raw;

import android.content.Intent;

import ohi.andre.consolelauncher.gallery.GalleryActivity;
import ohi.andre.consolelauncher.commands.CommandAbstraction;
import ohi.andre.consolelauncher.commands.ExecutePack;
import ohi.andre.consolelauncher.commands.main.MainPack;

public class gallery implements CommandAbstraction {

    @Override
    public String exec(ExecutePack pack) throws Exception {
        MainPack mainPack = (MainPack) pack;

        Intent intent = new Intent(mainPack.context, GalleryActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mainPack.context.startActivity(intent);
        return "Opening Gallery...";
    }

    @Override public int[] argType() { return new int[0]; }
    @Override public int priority() { return 4; }
    @Override public int helpRes() { return -1; }
    @Override public String onArgNotFound(ExecutePack pack, int index) { return null; }
    @Override public String onNotArgEnough(ExecutePack pack, int nArgs) { return null; }
}