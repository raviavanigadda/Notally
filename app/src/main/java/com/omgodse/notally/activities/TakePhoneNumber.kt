package com.omgodse.notally.activities

import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.widget.addTextChangedListener
import com.omgodse.notally.databinding.ActivityTakePhoneNumberBinding
import com.omgodse.notally.miscellaneous.bindLabels
import com.omgodse.notally.miscellaneous.getLocale
import com.omgodse.notally.miscellaneous.setOnNextAction
import com.omgodse.notally.viewmodels.BaseNoteModel
import com.omgodse.notally.viewmodels.TakePhoneNumberModel

class TakePhoneNumber : NotallyActivity() {

    override val model: TakePhoneNumberModel by viewModels()
    override val binding by lazy { ActivityTakePhoneNumberBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.EnterName.setOnNextAction {
            binding.EnterPhoneNumber.requestFocus()
        }

        setupListeners()
        setupToolbar(binding.Toolbar)

        if (model.isNewNote) {
            binding.EnterName.requestFocus()
        }

        setStateFromModel()
    }

    override fun getLabelGroup() = binding.LabelGroup

    override fun getPinnedIndicator() = binding.Pinned

    override fun getPinnedParent() = binding.LinearLayout

    override fun shareNote() = shareNote(model.name.toString(), model.phoneNumber.toString())

    private fun setupListeners() {

        binding.EnterName.addTextChangedListener(afterTextChanged = { editable ->
            model.name = editable
        })

        binding.EnterPhoneNumber.addTextChangedListener(afterTextChanged = { editable ->
            model.phoneNumber = editable
        })
    }

    private fun setStateFromModel() {
        val formatter = BaseNoteModel.getDateFormatter(getLocale())

        binding.EnterName.text = model.name
        binding.EnterPhoneNumber.text = model.phoneNumber
        binding.DateCreated.text = formatter.format(model.timestamp)

        binding.LabelGroup.bindLabels(model.labels)
    }

    companion object {
    }
}