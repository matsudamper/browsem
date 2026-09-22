package net.matsudamper.browser.ui.tabs

import androidx.annotation.DrawableRes
import net.matsudamper.browser.data.ProfileIcon
import net.matsudamper.browser.resources.R as ResourcesR

@DrawableRes
internal fun ProfileIcon.toDrawableRes(): Int {
    return when (this) {
        ProfileIcon.PERSON -> ResourcesR.drawable.ic_person_24dp
        ProfileIcon.WORK -> ResourcesR.drawable.ic_work_24dp
        ProfileIcon.SCHOOL -> ResourcesR.drawable.ic_school_24dp
        ProfileIcon.HOME -> ResourcesR.drawable.ic_home_24dp
        ProfileIcon.SHOPPING_CART -> ResourcesR.drawable.ic_shopping_cart_24dp
        ProfileIcon.STAR -> ResourcesR.drawable.ic_star_24dp
        ProfileIcon.FAVORITE -> ResourcesR.drawable.ic_favorite_24dp
    }
}
