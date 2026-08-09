package ohi.andre.consolelauncher.commands.main.raw;

import android.content.Intent;
import ohi.andre.consolelauncher.CalculatorActivity;
import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.commands.ExecutePack;
import ohi.andre.consolelauncher.commands.main.MainPack;
import ohi.andre.consolelauncher.commands.main.specific.ParamCommand;

public class calculator extends ParamCommand {

    @Override
    protected ohi.andre.consolelauncher.commands.main.Param paramForString(MainPack pack, String param) {
        return null;
    }

    @Override
    protected String doThings(ExecutePack pack) {
        Intent intent = new Intent(pack.context, CalculatorActivity.class);
        pack.context.startActivity(intent);
        return null;
    }

    @Override
    public String[] params() {
        return new String[0];
    }

    @Override
    public int priority() {
        return 3;
    }

    @Override
    public int helpRes() {
        return -1; // No help string needed
    }
}