package ohi.andre.consolelauncher.commands.main.raw;

import android.content.Intent;

import ohi.andre.consolelauncher.FileManagerActivity;
import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.commands.CommandAbstraction;
import ohi.andre.consolelauncher.commands.ExecutePack;
import ohi.andre.consolelauncher.commands.main.MainPack;

public class filemanager implements CommandAbstraction {

    @Override
    public String exec(ExecutePack pack) {
        MainPack mainPack = (MainPack) pack;
        Intent intent = new Intent(mainPack.context, FileManagerActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mainPack.context.startActivity(intent);
        return null;
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
        return R.string.help_filemanager;
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