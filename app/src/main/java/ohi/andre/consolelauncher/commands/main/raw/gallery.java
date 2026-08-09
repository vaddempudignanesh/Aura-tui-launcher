package ohi.andre.consolelauncher.commands.main.raw;

import android.content.Intent;
import ohi.andre.consolelauncher.GalleryActivity;
import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.commands.ExecutePack;
import ohi.andre.consolelauncher.commands.main.MainPack;
import ohi.andre.consolelauncher.commands.main.specific.ParamCommand;
import ohi.andre.consolelauncher.tuils.Tuils;

public class gallery extends ParamCommand {

    private enum Param implements ohi.andre.consolelauncher.commands.main.Param {
        // No parameters needed, just open gallery
        open {
            @Override
            public int[] args() {
                return new int[0];
            }

            @Override
            public String exec(ExecutePack pack) {
                Intent intent = new Intent(pack.context, GalleryActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                pack.context.startActivity(intent);
                return "Opening Gallery...";
            }

            @Override
            public String label() {
                return "";
            }

            @Override
            public String onNotArgEnough(ExecutePack pack, int n) {
                return null;
            }

            @Override
            public String onArgNotFound(ExecutePack pack, int index) {
                return null;
            }
        };

        static Param get(String p) {
            if (p == null || p.length() == 0) return open;
            Param[] ps = values();
            for (Param p1 : ps) {
                if (p1.label().equals(p)) return p1;
            }
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
            return null;
        }

        @Override
        public String onArgNotFound(ExecutePack pack, int index) {
            return null;
        }

        @Override
        public int[] args() {
            return new int[0];
        }
    }

    @Override
    public String[] params() {
        return Param.labels();
    }

    @Override
    protected ohi.andre.consolelauncher.commands.main.Param paramForString(MainPack pack, String param) {
        return Param.get(param);
    }

    @Override
    protected String doThings(ExecutePack pack) {
        // Default action - open gallery
        Intent intent = new Intent(pack.context, GalleryActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        pack.context.startActivity(intent);
        return "Opening Gallery...";
    }

    @Override
    public int priority() {
        return 4;
    }

    @Override
    public int helpRes() {
        return -1; // No help needed
    }
}