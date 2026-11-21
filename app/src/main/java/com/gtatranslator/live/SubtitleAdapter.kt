package com.gtatranslator.live

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class SubtitleAdapter(
    private val onReplayClick: (SubtitleItem) -> Unit
) : RecyclerView.Adapter<SubtitleAdapter.ViewHolder>() {

    private val items = mutableListOf<SubtitleItem>()
    var fontSizeSp: Float = 24f
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    var showOriginal: Boolean = true
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSpeakerBadge: TextView = view.findViewById(R.id.tvSpeakerBadge)
        val tvTimestamp: TextView = view.findViewById(R.id.tvTimestamp)
        val btnReplayItem: TextView = view.findViewById(R.id.btnReplayItem)
        val tvTranslated: TextView = view.findViewById(R.id.tvTranslated)
        val tvOriginal: TextView = view.findViewById(R.id.tvOriginal)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_subtitle, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        val tagSuffix = if (item.sourceTag.isNotBlank()) " • ${item.sourceTag}" else ""

        when (item.gender) {
            SpeakerGender.MALE -> {
                holder.tvSpeakerBadge.text = "JASON • MUŽ$tagSuffix"
                holder.tvSpeakerBadge.setBackgroundResource(R.drawable.bg_badge_male)
                holder.tvSpeakerBadge.setTextColor(ContextCompat.getColor(ctx, R.color.accent_cyan))
            }
            SpeakerGender.FEMALE -> {
                holder.tvSpeakerBadge.text = "LUCIA • ŽENA$tagSuffix"
                holder.tvSpeakerBadge.setBackgroundResource(R.drawable.bg_badge_female)
                holder.tvSpeakerBadge.setTextColor(ContextCompat.getColor(ctx, R.color.accent_pink))
            }
            SpeakerGender.NEUTRAL -> {
                holder.tvSpeakerBadge.text = "VICE$tagSuffix"
                holder.tvSpeakerBadge.setBackgroundResource(R.drawable.bg_badge_neutral)
                holder.tvSpeakerBadge.setTextColor(ContextCompat.getColor(ctx, R.color.accent_amber))
            }
        }

        holder.tvTimestamp.text = item.timestamp
        holder.tvTranslated.text = item.translatedText
        holder.tvTranslated.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)

        if (showOriginal && item.originalText.isNotBlank()) {
            holder.tvOriginal.visibility = View.VISIBLE
            holder.tvOriginal.text = item.originalText
        } else {
            holder.tvOriginal.visibility = View.GONE
        }

        holder.btnReplayItem.setOnClickListener { onReplayClick(item) }
        holder.tvTranslated.setOnClickListener { onReplayClick(item) }
    }

    override fun getItemCount(): Int = items.size

    fun addItem(item: SubtitleItem) {
        items.add(item)
        if (items.size > 150) {
            items.removeAt(0)
            notifyItemRemoved(0)
        }
        notifyItemInserted(items.size - 1)
    }

    fun getLastItem(): SubtitleItem? = items.lastOrNull()

    fun clearAll() {
        val count = items.size
        items.clear()
        notifyItemRangeRemoved(0, count)
    }
}
