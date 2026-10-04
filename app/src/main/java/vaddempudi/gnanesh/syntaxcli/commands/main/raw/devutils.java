package vaddempudi.gnanesh.syntaxcli.commands.main.raw;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.List;

import vaddempudi.gnanesh.syntaxcli.BuildConfig;
import vaddempudi.gnanesh.syntaxcli.R;
import vaddempudi.gnanesh.syntaxcli.commands.CommandAbstraction;
import vaddempudi.gnanesh.syntaxcli.commands.ExecutePack;
import vaddempudi.gnanesh.syntaxcli.commands.main.MainPack;
import vaddempudi.gnanesh.syntaxcli.commands.main.specific.ParamCommand;
import vaddempudi.gnanesh.syntaxcli.tuils.Tuils;

/**
 * Created by francescoandreuzzi on 22/08/2017.
 */

public class devutils extends ParamCommand {

    private enum Param implements vaddempudi.gnanesh.syntaxcli.commands.main.Param {
        notify {
            @Override
            public String exec(ExecutePack pack) {
                List<String> text = pack.getList();

                String title, txt = null;
                if(text.size() == 0) return null;
                else {
                    title = text.remove(0);
                    if(text.size() >= 2) txt = Tuils.toPlanString(text, Tuils.SPACE);
                }

                NotificationManagerCompat.from(pack.context).notify(200,
                        new NotificationCompat.Builder(pack.context)
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setContentTitle(title)
                            .setContentText(txt)
                            .build());

                return null;
            }

            @Override
            public int[] args() {
                return new int[] {CommandAbstraction.TEXTLIST};
            }
        },
        check_notifications {
            @Override
            public int[] args() {
                return new int[0];
            }

            @Override
            public String exec(ExecutePack pack) {
                return "Notification access: " + NotificationManagerCompat.getEnabledListenerPackages(pack.context).contains(BuildConfig.APPLICATION_ID) + Tuils.NEWLINE + "Notification service running: " + Tuils.notificationServiceIsRunning(pack.context);
            }
        };

        static Param get(String p) {
            p = p.toLowerCase();
            Param[] ps = values();
            for (Param p1 : ps)
                if (p.endsWith(p1.label()))
                    return p1;
            return null;
        }

        static String[] labels() {
            Param[] ps = values();
            String[] ss = new String[ps.length];

            for (int count = 0; count < ps.length; count++) {
                ss[count] = ps[count].label();
            }

            return ss;
        }

        @Override
        public String label() {
            return Tuils.MINUS + name();
        }

        @Override
        public String onNotArgEnough(ExecutePack pack, int n) {
            return pack.context.getString(R.string.help_devutils);
        }

        @Override
        public String onArgNotFound(ExecutePack pack, int index) {
            return null;
        }
    }

    @Override
    protected vaddempudi.gnanesh.syntaxcli.commands.main.Param paramForString(MainPack pack, String param) {
        return Param.get(param);
    }

    @Override
    public int priority() {
        return 2;
    }

    @Override
    public int helpRes() {
        return R.string.help_devutils;
    }

    @Override
    public String[] params() {
        return Param.labels();
    }

    @Override
    protected String doThings(ExecutePack pack) {
        return null;
    }
}
