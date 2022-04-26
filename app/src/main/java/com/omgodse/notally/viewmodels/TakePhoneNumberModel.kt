package com.omgodse.notally.viewmodels

import android.app.Application
import android.text.Editable
import android.text.SpannableStringBuilder
import com.omgodse.notally.room.BaseNote

class TakePhoneNumberModel(app: Application) : NotallyModel(app) {

    var name = Editable.Factory.getInstance().newEditable(String())
    var phoneNumber = Editable.Factory.getInstance().newEditable(String())

    override fun getBaseNote(): BaseNote {
        return BaseNote.createPhoneNumber(id, folder, name.toString().trimEnd(), pinned, timestamp, labels, phoneNumber.toString().trimEnd())
    }

    override fun setStateFromBaseNote(baseNote: BaseNote) {
        super.setStateFromBaseNote(baseNote)
        name = SpannableStringBuilder(baseNote.title)
        phoneNumber = SpannableStringBuilder(baseNote.body)
    }

}