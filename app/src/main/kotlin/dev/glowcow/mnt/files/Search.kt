package dev.glowcow.mnt.files

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Looks for files and folders by a part of their name. */
class Search(private val files: FileStore) {
    /** Everything under [roots] whose name holds [query], nearest folders first; what a hidden folder holds only with [hidden]. */
    fun find(roots: List<String>, query: String, hidden: Boolean): Flow<Node> = flow {
        val folders = ArrayDeque(roots)
        while (folders.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            for (node in files.list(folders.removeFirst()).orEmpty()) {
                if (node.hidden && !hidden) continue
                if (node.name.contains(query, ignoreCase = true)) emit(node)
                if (node.directory) folders.add(node.path)
            }
        }
    }
}
