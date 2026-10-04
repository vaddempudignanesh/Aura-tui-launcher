package vaddempudi.gnanesh.syntaxcli.commands.main.raw;

import java.io.File;

import vaddempudi.gnanesh.syntaxcli.R;
import vaddempudi.gnanesh.syntaxcli.commands.CommandAbstraction;
import vaddempudi.gnanesh.syntaxcli.commands.ExecutePack;
import vaddempudi.gnanesh.syntaxcli.commands.main.MainPack;
import vaddempudi.gnanesh.syntaxcli.managers.FileManager;
import vaddempudi.gnanesh.syntaxcli.tuils.Tuils;

public class open implements CommandAbstraction {

    @Override
    public String exec(ExecutePack pack) {
        MainPack info = (MainPack) pack;
        File file = info.get(File.class);

        int result = FileManager.openFile(info.context, file);

        if (result == FileManager.ISDIRECTORY) return info.res.getString(R.string.output_isdirectory);
        if (result == FileManager.IOERROR) return info.res.getString(R.string.output_error);

        return Tuils.EMPTYSTRING;
    }

    @Override
    public int helpRes() {
        return R.string.help_open;
    }

    @Override
    public int[] argType() {
        return new int[]{CommandAbstraction.FILE};
    }

    @Override
    public int priority() {
        return 4;
    }

    @Override
    public String onNotArgEnough(ExecutePack pack, int nArgs) {
        MainPack info = (MainPack) pack;
        return info.res.getString(helpRes());
    }

    @Override
    public String onArgNotFound(ExecutePack pack, int index) {
        MainPack info = (MainPack) pack;
        return info.res.getString(R.string.output_filenotfound);
    }

}
