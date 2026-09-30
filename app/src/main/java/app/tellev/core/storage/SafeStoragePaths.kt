package app.tellev.core.storage

import java.nio.file.Files
import java.nio.file.Path

/** Resolve a request-controlled filename or directory as a single child of its data root. */
internal fun safeStorageChild(root: Path, name: String, suffix: String = ""): Path {
    require(name.isNotBlank() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' }) {
        "Invalid storage name"
    }
    val directory = root.toAbsolutePath().normalize()
    val child = directory.resolve(name + suffix).normalize()
    require(child.startsWith(directory) && child.parent == directory && !Files.isSymbolicLink(child)) {
        "Storage path must stay inside its data directory"
    }
    return child
}
