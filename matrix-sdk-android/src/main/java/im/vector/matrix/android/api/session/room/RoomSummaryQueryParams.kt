/*
 * Copyright 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.matrix.android.api.session.room

import im.vector.matrix.android.api.query.QueryStringValue
import im.vector.matrix.android.api.session.room.model.Membership

fun roomSummaryQueryParams(init: (RoomSummaryQueryParams.Builder.() -> Unit) = {}): RoomSummaryQueryParams {
    return RoomSummaryQueryParams.Builder().apply(init).build()
}

/**
 * This class can be used to filter room summaries to use with:
 * [im.vector.matrix.android.api.session.room.Room] and [im.vector.matrix.android.api.session.room.RoomService]
 */
data class RoomSummaryQueryParams(
        val roomId: QueryStringValue,
        val displayName: QueryStringValue,
        val canonicalAlias: QueryStringValue,
        val memberships: List<Membership>
) {

    class Builder {

        var roomId: QueryStringValue = QueryStringValue.IsNotEmpty
        var displayName: QueryStringValue = QueryStringValue.IsNotEmpty
        var canonicalAlias: QueryStringValue = QueryStringValue.NoCondition
        var memberships: List<Membership> = Membership.all()

        fun build() = RoomSummaryQueryParams(
                roomId = roomId,
                displayName = displayName,
                canonicalAlias = canonicalAlias,
                memberships = memberships
        )
    }
}
