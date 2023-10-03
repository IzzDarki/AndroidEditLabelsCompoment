package com.izzdarki.editlabelscomponent

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.view.allViews
import com.izzdarki.editlabelscomponent.Utility.isViewHitByTouchEvent
import com.izzdarki.editlabelscomponent.Utility.showKeyboard
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.util.*
import kotlin.collections.HashMap

/**
 * Using this component enables an activity to create an UI for editing labels
 *
 * You need to do the following things
 * - override [Activity.dispatchTouchEvent] (see [EditLabelsComponent.dispatchTouchEvent] for further information)
 * - create an instance of this class in for example [Activity.onCreate]
 * - eventually call [displayLabels] to easily create and display initial labels
 *
 * @property separator Labels are not allowed to contain this String.
 *  That way it can be used as a separator when converting a list of labels to a single string
 * @property allLabels This list is used to create an auto-complete dropdown when editing labels.
 *  The dropdown contains only suggestions for labels that are not already added.
 *  When [allowNewLabels] is `false`, only labels that are already in [allLabels] are allowed.
 *  When [allowNewLabels] is `true`, new labels will **not** be added to [allLabels]
 * @property allowNewLabels See [allLabels]
 * @property onLabelAdded Called when a new label is added
 * @property onLabelRemoved Called when a label is removed
 * @property generateChip Function that is used to generate UI chips
 *  text, closeIcon behaviour, click and long click behaviour will always be configured be this component and overwrite anything done in this function
 * @property onCheckedChanged Called when a label is checked or unchecked, can also be used to style the chip
 * @property checkableFunctionality If `true`, the component provides functionality to check and uncheck labels.
 *  Use [currentLabelsCheckedMap] to get the checked status of all labels or [currentCheckedLabels], [currentUncheckedLabels] to get only checked or unchecked labels.
 *  Use [onCheckedChanged] to add a callback that is called when a label is checked or unchecked.
 *  Note that checkable functionality is completely independent from the [Chip.isChecked] and [Chip.isCheckable] attributes
 */
class EditLabelsComponent(
    private var labelsChipGroup: ChipGroup,
    private var labelsAddChip: Chip,
    allLabels: SortedSet<String> = sortedSetOf(),
    var allowNewLabels: Boolean = true,
    var separator: String = DEFAULT_SEPARATOR,
    var onLabelAdded: (label: String, isChecked: Boolean) -> Unit = { _, _ -> },
    var onLabelRemoved: (label: String, isChecked: Boolean) -> Unit = { _, _ -> },
    var generateChip: (context: Context, label: String, isChecked: Boolean) -> Chip = { context, _, _ -> generateChipDefault(context) },
    var onCheckedChanged: (label: String, isChecked: Boolean, chip: Chip) -> Unit = { _, _ , _-> },
    var checkableFunctionality: Boolean = false
) {
    private val context get() = labelsAddChip.context
    private val internalCurrentLabels = mutableMapOf<String, Boolean>()
    private var currentlyEditedLabel: String? = null

    var allLabels: SortedSet<String> = sortedSetOf()
        set(value) {
            field = value

            // Remove all chips, that are no longer in allLabels
            for (view in labelsChipGroup.allViews) {
                if (view is Chip && view != labelsAddChip && view.text !in allLabels) {
                    labelsChipGroup.removeView(view)
                    internalRemoveLabel(view.text.toString())
                }
            }
        }

    init {
        this.allLabels = allLabels
        // initialize labels add chip
        labelsAddChip.setOnClickListener {
            addEditTextToLabels(context.getString(R.string.new_label))
        }
    }

    /**
     * Displays given labels
     * @param labels List of labels to display
     */
    fun displayLabels(labels: Collection<String>) = displayLabelsWithCheckedStatus(labels.map { Pair(it, IS_CHECKED_DEFAULT) })

    fun displayLabelsWithCheckedStatus(labels: Collection<Pair<String, Boolean>>) {
        for ((label, isChecked) in labels) {
            // Add label chip at the end
            addChipToLabelsWithoutCallback(label, isChecked, internalCurrentLabels.size + 1)
            internalCurrentLabels[label] = isChecked
        }
    }

    /**
     * Set callback that is called when a label is added or removed to the component
     * Overwrites [onLabelAdded], [onLabelRemoved]
     */
    fun setOnLabelChanged(callback: (label: String, isChecked: Boolean, removed: Boolean) -> Unit) {
        onLabelAdded = { label, isChecked -> callback(label, isChecked, false) }
        onLabelRemoved = { label, isChecked -> callback(label, isChecked, true) }
    }

    /**
     * Get the chip that corresponds to a given `label`, or `null` if there is no such chip (which is also the case when the given `label` is currently edited)
     */
    fun getChipOfLabel(label: String): Chip? {
        return labelsChipGroup.allViews.firstOrNull { it is Chip && it !== labelsAddChip && it.text == label } as? Chip
    }

    /**
     * Get the labels, that are currently added and visible to the user
     */
    val currentLabels get() = internalCurrentLabels.keys.toSet()

    /**
     * Get the labels, that are currently added and visible to the user together with their checked status
     */
    val currentLabelsCheckedMap get() = internalCurrentLabels.toMap()

    /**
     * Get all checked labels, that are currently added and visible to the user
     */
    val currentCheckedLabels get() = internalCurrentLabels
        .filter { (_, checked) -> checked }
        .keys.toSet()

    /**
     * Get all unchecked labels, that are currently added and visible to the user
     */
    val currentUncheckedLabels get() = internalCurrentLabels
        .filter { (_, isChecked) -> !isChecked }
        .keys.toSet()

    /**
     * For being able to finish editing chips when the user clicks elsewhere,
     * activities must override [Activity.dispatchTouchEvent] and call this method from there.
     * It finishes editing in certain situations
     *
     * @return
     *  `false`, when touch event should be processed as usual => your overridden `dispatchTouchEvent` needs to call `super.dispatchTouchEvent` and return its value
     *  `true`, when the touch event should be consumed => your overridden `dispatchTouchEvent` also needs to return `true`,
     *
     *  You can use this code:
        ```kotlin
        override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
            // Every touch event goes through this function
            if (yourEditLabelsComponent.dispatchTouchEvent(ev))
            return true
            else
            return super.dispatchTouchEvent(ev)
        }
        ```
     */
    fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        // This function finishes editing of a label in certain situations

        if (ev?.actionMasked == MotionEvent.ACTION_DOWN) {

            // Check if touch event hits the edited label => don't finish editing it (the user wants to interact with the edited label)
            val editText = getEditTextFromChipGroup()
                ?: return false // if there is no EditText, touch events can be dispatched as usual

            if (!isViewHitByTouchEvent(editText, ev)) {
                getEditTextHitByTouchEvent(ev)?.requestFocus() // request focus to EditText if the touch event hits any EditText (before the focus gets cleared by finishEditingChip)
                finishEditingChip(editText)
            }

            // Check if touch event hits one of the chips => consume the touch event
            for (view in labelsChipGroup.allViews) {
                if (view is Chip && isViewHitByTouchEvent(view, ev)) {
                    return true // consume the touch event (finishing editing while also triggering other chip related UI is too much for a single touch)
                }
            }
        }
        return false // dispatch touch events as usual
    }



    private fun addChipToLabels(label: String, isChecked: Boolean, index: Int = 1) {
        addChipToLabelsWithoutCallback(label, isChecked, index)
        internalAddLabel(label, isChecked) // only add label internally if it is not already added
    }

    private fun addChipToLabelsWithoutCallback(text: String, isChecked: Boolean, index: Int = 1) {
        val chip = generateChip(context, text, isChecked) // generate chip using function given in constructor
        chip.text = text
        if (checkableFunctionality) {
            chip.setOnClickListener {
                val nowChecked = internalCurrentLabels[text]!!.not()
                internalCurrentLabels[text] = nowChecked
                onCheckedChanged(text, nowChecked, chip) // can be used to style the chip
                Log.d("asdf", "addChipToLabelsWithoutCallback: $currentCheckedLabels, $currentUncheckedLabels")
            }
        }
        chip.isCloseIconVisible = true // this functionality is essential to this UI component
        chip.setOnCloseIconClickListener {
            labelsChipGroup.removeView(chip)
            internalRemoveLabel(text)
        }
        chip.setOnLongClickListener {
            startEditingChip(chip)
            return@setOnLongClickListener true // consumed long click
        }
        labelsChipGroup.addView(chip, index)
    }

    private fun addEditTextToLabels(text: String, index: Int = 1) {
        val editText = AutoCompleteTextView(context)
        editText.isSingleLine = true
        editText.setText(text)
        editText.setSelectAllOnFocus(true)

        editText.imeOptions = EditorInfo.IME_ACTION_DONE
        editText.setOnEditorActionListener { _, _, _ ->
            // when action (done) triggered, finish editing
            finishEditingChip(editText)
            return@setOnEditorActionListener true // consumed the action
        }

        editText.setAdapter(
            ArrayAdapter(context, R.layout.auto_complete_dropdown_item, allLabels.filter {
                it !in internalCurrentLabels.keys
            })
        )
        editText.onItemClickListener = AdapterView.OnItemClickListener { _, _, _, _ ->
            finishEditingChip(editText)
        }
        editText.minWidth = context.resources.getDimension(R.dimen.labels_edit_text_min_width).toInt() // This looks better than changing the EditText.dropdownWidth
        editText.threshold = 1
        editText.showDropDown()

        labelsChipGroup.addView(editText, index)
        editText.requestFocus()
        showKeyboard(editText)
    }

    private fun startEditingChip(chip: Chip) {
        currentlyEditedLabel = chip.text.toString()
        val index = labelsChipGroup.indexOfChild(chip)
        labelsChipGroup.removeView(chip)
        addEditTextToLabels(chip.text.toString(), index)
    }

    private fun finishEditingChip(editText: AutoCompleteTextView) {
        // clear focus and remove editText
        editText.clearFocus()
        val index = labelsChipGroup.indexOfChild(editText)
        labelsChipGroup.removeView(editText)

        val newLabel = editText.text.toString().trim()

        if (isNewLabelOkOrShowError(newLabel))
            addChipToLabels(newLabel, internalCurrentLabels[currentlyEditedLabel] ?: IS_CHECKED_DEFAULT, index)
        else if (currentlyEditedLabel != null) { // should always be true
            val labelBeforeEdit = currentlyEditedLabel!!
            currentlyEditedLabel = null
            addChipToLabelsWithoutCallback(labelBeforeEdit, internalCurrentLabels[labelBeforeEdit] ?: IS_CHECKED_DEFAULT, index)
        }
    }

    /**
     * Adds label internally and calls [onLabelAdded],
     * but only if the label is not already added
     */
    private fun internalAddLabel(label: String, isChecked: Boolean) {
        if (label !in internalCurrentLabels.keys) {
            if (currentlyEditedLabel != null) {
                val labelBeforeEdit = currentlyEditedLabel!!
                currentlyEditedLabel = null
                internalRemoveLabel(labelBeforeEdit)
            }
            internalCurrentLabels[label] = isChecked
            onLabelAdded(label, isChecked)
        }
    }

    /**
     * Removes label internally and calls [onLabelRemoved],
     * but only if the label was added before
     */
    private fun internalRemoveLabel(label: String) {
        if (label in internalCurrentLabels.keys) {
            val wasChecked = internalCurrentLabels[label]!!
            internalCurrentLabels.remove(label)
            onLabelRemoved(label, wasChecked)
        }
    }

    private fun getEditTextFromChipGroup(): AutoCompleteTextView? {
        return labelsChipGroup.allViews.firstOrNull { it is AutoCompleteTextView } as? AutoCompleteTextView
    }

    private fun getEditTextHitByTouchEvent(ev: MotionEvent): EditText? {
        return labelsChipGroup.rootView.allViews.firstOrNull {
            it is EditText && isViewHitByTouchEvent(it, ev)
        } as? EditText
    }

    private fun isNewLabelOkOrShowError(newLabel: String): Boolean {
        if (newLabel == "") {
            Toast.makeText(context, R.string.error_label_cant_be_empty, Toast.LENGTH_SHORT).show()
            return false
        } else if (newLabel.contains(separator)) {
            val errorMessage = String.format(
                context.getString(R.string.error_label_cant_contain_x),
                separator
            )
            Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
            return false
        } else if (newLabel in internalCurrentLabels.keys && newLabel != currentlyEditedLabel) {
            Toast.makeText(context, R.string.error_label_already_added, Toast.LENGTH_SHORT).show()
            return false
        } else if (!allowNewLabels && newLabel !in allLabels) {
            Toast.makeText(context, R.string.label_does_not_exist, Toast.LENGTH_SHORT).show()
            return false
        }

        return true
    }

    companion object {
        const val DEFAULT_SEPARATOR = "§]7%}$"
        const val IS_CHECKED_DEFAULT = true
        fun generateChipDefault(context: Context): Chip {
            val chip = Chip(context)
            chip.isCheckable = false
            return chip
        }
    }
}