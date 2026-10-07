package io.github.VincentL0L.VARraces.teavm;

import java.io.File;
import java.io.IOException;

import org.teavm.tooling.TeaVMTool;
import org.teavm.vm.TeaVMOptimizationLevel;

import com.github.xpenatan.gdx.backends.teavm.config.AssetFileHandle;
import com.github.xpenatan.gdx.backends.teavm.config.TeaBuildConfiguration;
import com.github.xpenatan.gdx.backends.teavm.config.TeaBuilder;

/**
 * Compiles the game to JavaScript and copies the assets into build/dist/webapp
 * Run with: ./gradlew teavm:buildJavaScript
 */
public class TeaVMBuilder {
    public static void main(String[] args) throws IOException {
        TeaBuildConfiguration config = new TeaBuildConfiguration();
        config.assetsPath.add(new AssetFileHandle("../assets"));
        config.webappPath = new File("build/dist").getCanonicalPath();
        config.htmlTitle = "VAR Races";
        config.htmlWidth = 1280;
        config.htmlHeight = 720;
        config.useDefaultHtmlIndex = true;
        config.showLoadingLogo = false;

        TeaVMTool tool = TeaBuilder.config(config);
        tool.setMainClass(TeaVMLauncher.class.getName());
        tool.setObfuscated(true);
        tool.setOptimizationLevel(TeaVMOptimizationLevel.ADVANCED);
        if (!TeaBuilder.build(tool)) {
            System.exit(1);
        }
    }
}
