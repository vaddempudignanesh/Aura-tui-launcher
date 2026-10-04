package vaddempudi.gnanesh.syntaxcli.tuils;

import androidx.core.content.FileProvider;

import vaddempudi.gnanesh.syntaxcli.BuildConfig;

public class GenericFileProvider extends FileProvider {
    public static final String PROVIDER_NAME = BuildConfig.APPLICATION_ID + ".FILE_PROVIDER";
}
