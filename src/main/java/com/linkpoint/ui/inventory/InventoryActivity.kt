package com.linkpoint.ui.inventory

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.linkpoint.LinkpointApp
import com.linkpoint.inventory.InventoryFolder
import com.linkpoint.inventory.InventoryItem
import com.linkpoint.R
import com.linkpoint.teleport.TeleportResult
import java.util.UUID
import android.content.Intent
import com.linkpoint.ui.notecard.NotecardEditorActivity

/**
 * Inventory Activity - Browse and manage inventory
 * Based on the reference viewer's InventoryActivity
 *
 * Legacy entry point retained during Compose migration.
 * Removal target: 2026.09.
 */
@Deprecated(
    message = "Legacy Activity entry point. Use InventoryScreen-based Compose navigation."
)
class InventoryActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "InventoryActivity"
    }
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var breadcrumbText: TextView
    private lateinit var emptyText: TextView
    
    private val inventoryStack = mutableListOf<InventoryFolder>()
    private val currentItems = mutableListOf<ActivityInventoryItem>()
    private lateinit var adapter: ActivityInventoryAdapter
    
    private val app by lazy { LinkpointApp.getInstance() }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inventory)
        
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Inventory"
        }
        
        initViews()
        loadRootInventory()
    }
    
    private fun initViews() {
        recyclerView = findViewById(R.id.inventoryRecyclerView)
        breadcrumbText = findViewById(R.id.breadcrumbText)
        emptyText = findViewById(R.id.emptyText)
        
        adapter = ActivityInventoryAdapter(currentItems) { item ->
            onItemClicked(item)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }
    
    private fun loadRootInventory() {
        val rootId = app.inventoryManager.getSystemFolder(
            com.linkpoint.inventory.InventoryManager.FOLDER_TYPE_ROOT
        )

        if (rootId == null) {
            emptyText.visibility = View.VISIBLE
            emptyText.text = "Second Life inventory is not available yet."
            return
        }

        lifecycleScope.launch {
            try {
                val ok = app.inventoryManager.fetchFolderContents(rootId, fetchFolders = true, fetchItems = true)
                if (!ok) {
                    emptyText.visibility = View.VISIBLE
                    emptyText.text = "Could not load your Second Life inventory."
                    return@launch
                }
                val root = app.inventoryManager.getFolder(rootId)
                if (root == null) {
                    emptyText.visibility = View.VISIBLE
                    emptyText.text = "Second Life root inventory folder was not received."
                    return@launch
                }
                inventoryStack.clear()
                inventoryStack.add(root)
                loadFolderContents(root)
            } catch (e: Exception) {
                emptyText.visibility = View.VISIBLE
                emptyText.text = "Inventory error: ${e.message ?: "unknown error"}"
            }
        }
    }

    private fun loadFolderContents(folder: InventoryFolder) {
        updateBreadcrumb()
        currentItems.clear()

        app.inventoryManager.getFolders(folder.folderId).forEach { child ->
            currentItems.add(ActivityInventoryItem.folder(child.folderId, child.name, child.parentId))
        }
        app.inventoryManager.getItems(folder.folderId).forEach { item ->
            currentItems.add(item.toActivityItem())
        }

        emptyText.visibility = if (currentItems.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = if (currentItems.isEmpty()) "This folder is empty." else ""
        adapter.notifyDataSetChanged()
    }

    private fun openFolder(folderId: UUID) {
        lifecycleScope.launch {
            try {
                val ok = app.inventoryManager.fetchFolderContents(folderId, fetchFolders = true, fetchItems = true)
                if (!ok) {
                    Toast.makeText(this@InventoryActivity, "Could not load folder contents.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val folder = app.inventoryManager.getFolder(folderId)
                if (folder == null) {
                    Toast.makeText(this@InventoryActivity, "Folder data was not received.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                inventoryStack.add(folder)
                loadFolderContents(folder)
            } catch (e: Exception) {
                Toast.makeText(this@InventoryActivity, "Inventory error: ${e.message ?: "unknown error"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateBreadcrumb() {
        breadcrumbText.text = inventoryStack.joinToString(" > ") { it.name }
    }
    
    private fun onItemClicked(item: ActivityInventoryItem) {
        when (item.type) {
            InventoryType.FOLDER -> {
                openFolder(item.id)
            }
            InventoryType.NOTECARD -> {
                // Open notecard editor
                openNotecard(item)
            }
            InventoryType.LANDMARK -> {
                // Offer to teleport
                offerTeleport(item)
            }
            else -> {
                // Show item details
                showItemDetails(item)
            }
        }
    }
    
    private fun openNotecard(item: ActivityInventoryItem) {
        val intent = Intent(this, NotecardEditorActivity::class.java).apply {
            putExtra(NotecardEditorActivity.EXTRA_ITEM_ID, item.id.toString())
            putExtra(NotecardEditorActivity.EXTRA_NOTECARD_NAME, item.name)
            item.assetId?.let { putExtra(NotecardEditorActivity.EXTRA_ASSET_ID, it.toString()) }
            item.taskId?.let { putExtra(NotecardEditorActivity.EXTRA_TASK_ID, it.toString()) }
            item.objectId?.let { putExtra(NotecardEditorActivity.EXTRA_OBJECT_ID, it.toString()) }

            // Allow editing by default for now
            putExtra(NotecardEditorActivity.EXTRA_READ_ONLY, false)
        }
        startActivity(intent)
    }
    
    private fun offerTeleport(item: ActivityInventoryItem) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Teleport")
            .setMessage("Teleport to ${item.name}?")
            .setPositiveButton("Teleport") { _, _ ->
                if (app.isTeleportManagerInitialized()) {
                    lifecycleScope.launch {
                        val result = app.teleportManager.teleportToLandmark(item.assetId ?: item.id)
                        when (result) {
                            is TeleportResult.Success -> {
                                Toast.makeText(this@InventoryActivity, "Teleporting to ${result.regionName}...", Toast.LENGTH_SHORT).show()
                            }
                            is TeleportResult.Failure -> {
                                Toast.makeText(this@InventoryActivity, result.message, Toast.LENGTH_SHORT).show()
                            }
                            is TeleportResult.Pending -> {
                                Toast.makeText(this@InventoryActivity, "Teleport requested...", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    Toast.makeText(this, "Teleport manager not initialized", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showItemDetails(item: ActivityInventoryItem) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage("Type: ${item.type.displayName}\nCreator: ${item.creatorId ?: "Unknown"}")
            .setPositiveButton("OK", null)
            .show()
    }
    
    override fun onBackPressed() {
        if (inventoryStack.size > 1) {
            // Avoid MutableList.removeLast() — it resolves to the Java 21
            // SequencedCollection default method, which is missing on
            // Android < 35 and throws NoSuchMethodError at runtime.
            inventoryStack.removeAt(inventoryStack.lastIndex)
            loadFolderContents(inventoryStack.last())
        } else {
            super.onBackPressed()
        }
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

enum class InventoryType(val displayName: String, val icon: Int) {
    FOLDER("Folder", R.drawable.ic_folder),
    TEXTURE("Texture", R.drawable.ic_texture),
    SOUND("Sound", R.drawable.ic_sound),
    ANIMATION("Animation", R.drawable.ic_animation),
    GESTURE("Gesture", R.drawable.ic_gesture),
    LANDMARK("Landmark", R.drawable.ic_landmark),
    CLOTHING("Clothing", R.drawable.ic_clothing),
    BODYPART("Body Part", R.drawable.ic_bodypart),
    OBJECT("Object", R.drawable.ic_object),
    NOTECARD("Notecard", R.drawable.ic_notecard),
    SCRIPT("Script", R.drawable.ic_script),
    CALLING_CARD("Calling Card", R.drawable.ic_calling_card),
    LINK("Link", R.drawable.ic_link),
    UNKNOWN("Unknown", R.drawable.ic_unknown)
}

// Local data classes for this activity (avoiding conflict with InventoryManager classes)
data class ActivityInventoryFolder(
    val id: UUID,
    val name: String,
    val parentId: UUID?
)

private fun InventoryItem.toActivityItem(): ActivityInventoryItem = ActivityInventoryItem(
    id = itemId,
    name = name,
    parentId = parentId,
    type = inventoryType.toInventoryType(),
    assetId = assetId.takeUnless { it == UUID(0L, 0L) },
    creatorId = permissions.creatorId
)

private fun Int.toInventoryType(): InventoryType = when (this) {
    0 -> InventoryType.TEXTURE
    1 -> InventoryType.SOUND
    2 -> InventoryType.CALLING_CARD
    3 -> InventoryType.LANDMARK
    6 -> InventoryType.OBJECT
    7 -> InventoryType.NOTECARD
    10 -> InventoryType.SCRIPT
    15 -> InventoryType.TEXTURE
    18 -> InventoryType.CLOTHING
    19 -> InventoryType.ANIMATION
    20 -> InventoryType.GESTURE
    24 -> InventoryType.LINK
    else -> InventoryType.UNKNOWN
}

data class ActivityInventoryItem(
    val id: UUID,
    val name: String,
    val parentId: UUID?,
    val type: InventoryType,
    val assetId: UUID? = null,
    val taskId: UUID? = null,
    val objectId: UUID? = null,
    val creatorId: UUID? = null,
    val permissions: Int = 0
) {
    companion object {
        fun folder(id: UUID, name: String, parentId: UUID?): ActivityInventoryItem {
            return ActivityInventoryItem(id, name, parentId, InventoryType.FOLDER)
        }
    }
}

class ActivityInventoryAdapter(
    private val items: List<ActivityInventoryItem>,
    private val onItemClick: (ActivityInventoryItem) -> Unit
) : RecyclerView.Adapter<ActivityInventoryAdapter.ViewHolder>() {
    
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val iconView: ImageView = view.findViewById(R.id.itemIcon)
        val nameText: TextView = view.findViewById(R.id.itemName)
        val infoText: TextView = view.findViewById(R.id.itemInfo)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_inventory, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        
        holder.nameText.text = item.name
        holder.infoText.text = if (item.type == InventoryType.FOLDER) "" else item.type.displayName
        holder.iconView.setImageResource(item.type.icon)
        
        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }
    
    override fun getItemCount() = items.size
}
