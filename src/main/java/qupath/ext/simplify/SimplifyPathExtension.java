package qupath.ext.simplify;

import javafx.scene.control.MenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * QuPath extension entry point. Adds a "Smooth annotations" command under
 * <i>Extensions &gt; Smooth Annotation</i> that smooths and simplifies the selected
 * annotations' outlines using Inkscape-style cubic Bézier fitting (or a spline
 * alternative), avoiding the harsh angles produced by QuPath's built-in
 * vertex-removal simplifier.
 */
public class SimplifyPathExtension implements QuPathExtension {

    private static final Logger logger = LoggerFactory.getLogger(SimplifyPathExtension.class);

    private static final String EXTENSION_NAME = "Smooth Annotation";
    private static final String EXTENSION_DESCRIPTION =
            "Smooth and simplify annotation outlines using Inkscape-style curve fitting.";
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.7.0");

    private boolean isInstalled = false;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (isInstalled) {
            logger.debug("{} is already installed", getName());
            return;
        }
        isInstalled = true;

        var command = new SimplifyPathCommand(qupath);
        MenuItem menuItem = new MenuItem("Smooth annotations…");
        menuItem.setOnAction(e -> command.run());

        var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
        menu.getItems().add(menuItem);
    }

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public String getDescription() {
        return EXTENSION_DESCRIPTION;
    }

    @Override
    public Version getQuPathVersion() {
        return EXTENSION_QUPATH_VERSION;
    }
}
