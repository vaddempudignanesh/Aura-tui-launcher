package vaddempudi.gnanesh.syntaxcli.tuils.interfaces;

import vaddempudi.gnanesh.syntaxcli.commands.main.specific.RedirectCommand;

/**
 * Created by francescoandreuzzi on 03/03/2017.
 */

public interface OnRedirectionListener {

    void onRedirectionRequest(RedirectCommand cmd);
    void onRedirectionEnd(RedirectCommand cmd);
}
