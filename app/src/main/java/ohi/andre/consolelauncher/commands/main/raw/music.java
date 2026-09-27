package ohi.andre.consolelauncher.commands.main.raw;

import android.content.Intent;

import ohi.andre.consolelauncher.MusicPlayerActivity;
import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.commands.CommandAbstraction;
import ohi.andre.consolelauncher.commands.ExecutePack;
import ohi.andre.consolelauncher.commands.main.MainPack;

/**
 * TUI command that launches the built-in Music Player activity.
 *
 * Usage from the terminal:
 *     mplayer
 *
 * It appears in suggestions automatically because it lives in the
 * commands/main/raw package and implements CommandAbstraction.
 */
public class music implements CommandAbstraction {

    @Override
    public String exec(ExecutePack pack) {
        MainPack main = (MainPack) pack;
        try {
            Intent i = new Intent(main.context, MusicPlayerActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            main.context.startActivity(i);
            return null;
        } catch (Exception e) {
            return "Cannot open music player: " + e.getMessage();
        }
    }

    @Override
    public int[] argType() {
        return new int[0];
    }

    @Override
    public int priority() {
        return 3;
    }

    @Override
    public int helpRes() {
        return R.string.help_mplayer;
    }

    @Override
    public String onArgNotFound(ExecutePack pack, int index) {
        return null;
    }

    @Override
    public String onNotArgEnough(ExecutePack pack, int nArgs) {
        return null;
    }
}