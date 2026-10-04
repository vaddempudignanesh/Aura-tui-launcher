package vaddempudi.gnanesh.syntaxcli.commands.main.raw;

import vaddempudi.gnanesh.syntaxcli.R;
import vaddempudi.gnanesh.syntaxcli.commands.CommandAbstraction;
import vaddempudi.gnanesh.syntaxcli.commands.ExecutePack;
import vaddempudi.gnanesh.syntaxcli.managers.xml.XMLPrefsManager;
import vaddempudi.gnanesh.syntaxcli.managers.xml.options.Ui;
import vaddempudi.gnanesh.syntaxcli.tuils.interfaces.Reloadable;

public class username implements CommandAbstraction {

    @Override
    public String exec(ExecutePack pack) {
        String newUser = pack.getString();
        String newDevice = pack.getString();

        if (newUser == null || newDevice == null) {
            return onNotArgEnough(pack, 0);
        }

        XMLPrefsManager.XMLPrefsRoot.UI.write(Ui.username, newUser);
        XMLPrefsManager.XMLPrefsRoot.UI.write(Ui.deviceName, newDevice);

        try {
            if (pack.context instanceof Reloadable) {
                ((Reloadable) pack.context).reload();
            }
        } catch (Exception e) {}

        return "Username and Device updated!";
    }

    @Override
    public int[] argType() {
        return new int[] {CommandAbstraction.NO_SPACE_STRING, CommandAbstraction.NO_SPACE_STRING};
    }

    @Override
    public int priority() {
        return 3;
    }

    @Override
    public int helpRes() {
        return R.string.help_username;
    }

    @Override
    public String onNotArgEnough(ExecutePack pack, int n) {
        return pack.context.getString(R.string.help_username);
    }

    @Override
    public String onArgNotFound(ExecutePack pack, int index) {
        return null;
    }
}
