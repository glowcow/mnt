package dev.glowcow.mnt.files

import dev.glowcow.mnt.archive.Archives
import dev.glowcow.mnt.net.Remotes

/** Where a path starts: a mounted [volume], or a place outside the phone's file system. */
class Root(val path: String, val label: String, val volume: Volume?)

/** Tells of any path where it starts and how a person reads it. */
class Roots(private val volumes: Volumes, private val remotes: Remotes) {
    fun of(path: String): Root? {
        val id = Remote.id(path) ?: return volumes.of(path)?.let { Root(it.path, it.label, it) }
        Archives.archive(id)?.let { return Root(Remote.root(id), Paths.name(it), null) }
        return remotes.title(id)?.let { Root(Remote.root(id), it, null) }
    }

    /** [path] as a person reads it: where it starts by its name, then the folders down to it. */
    fun display(path: String): String {
        // In an archive: the archive's own way, then the way inside it.
        Archives.of(path)?.let { return display(it) + Remote.inner(path).takeIf { inner -> inner != "/" }.orEmpty() }
        val root = of(path) ?: return path
        return root.label + path.removePrefix(root.path)
    }
}
