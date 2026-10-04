package vaddempudi.gnanesh.syntaxcli.commands.main.raw;

import android.content.Intent;

import vaddempudi.gnanesh.syntaxcli.calculator.CalculatorActivity;
import vaddempudi.gnanesh.syntaxcli.commands.CommandAbstraction;
import vaddempudi.gnanesh.syntaxcli.commands.ExecutePack;
import vaddempudi.gnanesh.syntaxcli.commands.main.MainPack;

public class calculator implements CommandAbstraction {

    @Override
    public String exec(ExecutePack pack) throws Exception {
        MainPack mainPack = (MainPack) pack;

        Intent intent = new Intent(mainPack.context, CalculatorActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        mainPack.context.startActivity(intent);
        return null;
    }

    @Override public int[] argType() { return new int[0]; }
    @Override public int priority() { return 3; }
    @Override public int helpRes() { return -1; }
    @Override public String onArgNotFound(ExecutePack pack, int index) { return null; }
    @Override public String onNotArgEnough(ExecutePack pack, int nArgs) { return null; }
}