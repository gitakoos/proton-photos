/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://www.photosforproton.eu
 *
 * This file is part of Photos for Proton.
 *
 * Photos for Proton is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package eu.akoos.photos.presentation.people

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import eu.akoos.photos.R
import eu.akoos.photos.domain.usecase.AssignFacesToPersonUseCase
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.gallery.FaceBox
import eu.akoos.photos.presentation.gallery.FaceCropTransformation
import eu.akoos.photos.presentation.gallery.LocalThumbnailUrls
import eu.akoos.photos.presentation.gallery.PersonUi
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.viewer.DetailsPeopleRow

/**
 * What [NameOrMergeSheet] asks about. [Face] is one face on a photo (a single Unsorted face): [Face.faceSource]
 * is the image model the photo loads from and [Face.faceBox] the face on it, as fractions of the image.
 * [Group] is an unnamed group of faces: [Group.photoCount] its photos (none shown when not above zero),
 * [Group.coverSource] its cover's image model and [Group.faceBox] the cover face. [Faces] is a selection of
 * photos being added to a person: the faces chosen on them, at most one per photo, and the photos that got
 * none ([Faces.manualPhotoKeys]), which join the same person without a face. With no source or no box the
 * sheet shows a person glyph where a face would be.
 */
sealed interface NameOrMergeSubject {
    data class Face(val faceId: String, val faceSource: Any?, val faceBox: FaceBox?) : NameOrMergeSubject

    data class Group(
        val personId: Long,
        val photoCount: Int,
        val coverSource: Any?,
        val faceBox: FaceBox?,
    ) : NameOrMergeSubject

    data class Faces(val faces: List<ChosenFace>, val manualPhotoKeys: List<String>) : NameOrMergeSubject
}

/** One face of [NameOrMergeSubject.Faces]: its id, the photo it is on ([photoKey]), and the image model and
 *  box its crop is drawn from. */
data class ChosenFace(val faceId: String, val photoKey: String, val faceSource: Any?, val faceBox: FaceBox?)

/**
 * The [NameOrMergeSubject.Group] for [person], its cover resolved the way the person's face tile resolves
 * it, so every surface that lists people hands the sheet the same face.
 */
@Composable
fun rememberGroupSubject(person: PersonUi): NameOrMergeSubject.Group {
    val cover = LocalThumbnailUrls.current.value[person.coverPhotoKey] ?: person.coverPhotoKey
    return remember(person, cover) {
        NameOrMergeSubject.Group(
            personId = person.personId,
            photoCount = person.faceCount,
            coverSource = cover,
            faceBox = person.faceBox,
        )
    }
}

/**
 * The one sheet for naming a nameless face or group, the same wherever it opens: the face and "Who is
 * this?" above the people picker. A face goes at once to the person picked, or to a new person under a
 * typed name, since moving a face is easily undone. A group takes a typed new name as its own, and joins a
 * person picked, or typed, only after a confirm, since a merge cannot be split back apart automatically.
 * A selection's faces go together to the person picked or typed, and its photos without a face follow;
 * a new name is offered only while at least one face is chosen, since a person with no face shows up
 * nowhere.
 *
 * On success [onDone] gets the person the subject now belongs to and the sheet closes; a failure keeps it
 * open with a short message. For a group, "See photos" hands [onOpenGroup] its id and closes the sheet.
 * Works the same with no account, on the device's own people.
 */
@Composable
fun NameOrMergeSheet(
    subject: NameOrMergeSubject,
    onDismiss: () -> Unit,
    onOpenGroup: (personId: Long) -> Unit = {},
    onDone: (personId: Long) -> Unit = {},
) {
    val viewModel: NameOrMergeViewModel = hiltViewModel()
    val context = LocalContext.current
    // A selection's key spans all its photos, so it is built once per subject.
    val subjectKey = remember(subject) { subject.key }
    val people by viewModel.people.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    var pendingMerge by remember(subjectKey) { mutableStateOf<PersonUi?>(null) }
    val currentOnDone by rememberUpdatedState(onDone)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    LaunchedEffect(viewModel, subjectKey) {
        viewModel.bind(subject)
        viewModel.outcomes.collect { outcome ->
            if (outcome.subjectKey != subjectKey) return@collect
            Toast.makeText(context, context.getString(outcome.message), Toast.LENGTH_SHORT).show()
            if (outcome is NameOrMergeViewModel.Outcome.Done) {
                currentOnDone(outcome.personId)
                currentOnDismiss()
            }
        }
    }

    fun pick(person: PersonUi) {
        if (working) return
        when (subject) {
            is NameOrMergeSubject.Face ->
                viewModel.assignFace(subject, AssignFacesToPersonUseCase.Target.Existing(person.personId))
            is NameOrMergeSubject.Faces ->
                viewModel.assignFaces(subject, AssignFacesToPersonUseCase.Target.Existing(person.personId))
            is NameOrMergeSubject.Group -> pendingMerge = person
        }
    }

    fun create(name: String) {
        if (working) return
        when (subject) {
            is NameOrMergeSubject.Face -> viewModel.assignFace(subject, AssignFacesToPersonUseCase.Target.Named(name))
            is NameOrMergeSubject.Faces -> viewModel.assignFaces(subject, AssignFacesToPersonUseCase.Target.Named(name))
            is NameOrMergeSubject.Group -> viewModel.nameGroup(subject, name)
        }
    }

    // Without a new name to offer, the field only searches and the empty list says how to get one.
    val canCreate = NameOrMergeRules.canCreate(subject)
    val createNew = { name: String -> create(name) }.takeIf { canCreate }

    // The confirm opens over the picker rather than in its place, so backing out of it returns to the
    // choice with the typed text still there.
    PersonPickerSheet(
        title = stringResource(R.string.name_or_merge_title),
        people = people,
        onPick = { pick(it) },
        onDismiss = onDismiss,
        onCreateNew = createNew,
        searchPlaceholder = if (canCreate) null else stringResource(R.string.people_search_hint),
        emptyText = if (canCreate) null else stringResource(R.string.add_to_person_needs_face),
        header = {
            NameOrMergeHeader(
                subject = subject,
                onSeePhotos = { personId ->
                    onOpenGroup(personId)
                    onDismiss()
                },
            )
        },
    )

    val mergeInto = pendingMerge
    if (mergeInto != null && subject is NameOrMergeSubject.Group) {
        ConfirmDialog(
            title = stringResource(R.string.person_merge_confirm_title),
            message = stringResource(R.string.name_or_merge_merge_body, mergeInto.displayName.orEmpty()),
            confirmLabel = stringResource(R.string.person_merge_confirm),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                pendingMerge = null
                viewModel.mergeGroup(subject, mergeInto)
            },
            onDismiss = { pendingMerge = null },
        )
    }
}

/** The top of the sheet: the face or faces asked about beside the question and, for a group, how many
 *  photos it holds with a way to look through them first. */
@Composable
private fun NameOrMergeHeader(subject: NameOrMergeSubject, onSeePhotos: (personId: Long) -> Unit) {
    val colors = AppColors.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        when (subject) {
            is NameOrMergeSubject.Face -> SubjectFace(source = subject.faceSource, box = subject.faceBox)
            is NameOrMergeSubject.Group -> SubjectFace(source = subject.coverSource, box = subject.faceBox)
            is NameOrMergeSubject.Faces -> SubjectFaces(subject.faces)
        }
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                stringResource(R.string.name_or_merge_title),
                color = colors.fgPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (subject is NameOrMergeSubject.Group && subject.photoCount > 0) {
                Text(
                    pluralStringResource(R.plurals.count_photos_plural, subject.photoCount, subject.photoCount),
                    color = colors.fgMute,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (subject is NameOrMergeSubject.Group) {
            TextButton(onClick = { onSeePhotos(subject.personId) }) {
                Text(
                    stringResource(R.string.name_or_merge_see_photos),
                    color = colors.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * A selection's chosen faces: one reads exactly as the single face does, and none shows the person glyph.
 * Several overlap in a short stack, the first on top and each ringed in the sheet's colour, with a count
 * in the last place when there are more than the stack holds ([NameOrMergeRules.faceStack]).
 */
@Composable
private fun SubjectFaces(faces: List<ChosenFace>) {
    if (faces.size <= 1) {
        val face = faces.firstOrNull()
        SubjectFace(source = face?.faceSource, box = face?.faceBox)
        return
    }
    val colors = AppColors.current
    val (shown, more) = NameOrMergeRules.faceStack(faces.size)
    val places = shown + if (more > 0) 1 else 0
    Box {
        for (i in 0 until places) {
            val place = Modifier
                .padding(start = StackStep * i)
                .zIndex((places - i).toFloat())
            if (i < shown) {
                val face = faces[i]
                SubjectFace(
                    source = face.faceSource,
                    box = face.faceBox,
                    modifier = place,
                    size = StackFaceSize,
                    ringed = true,
                )
            } else {
                Box(
                    modifier = place
                        .size(StackFaceSize)
                        .clip(CircleShape)
                        .background(colors.surfaceWeak)
                        .border(StackRing, colors.sheetBg, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "+$more",
                        color = colors.fgPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The round face crop, read the way the details panel's People row reads its crops, or the neutral glyph
 *  where there is nothing to crop it from. [ringed] draws the ring in the sheet's colour that parts one face
 *  of a stack from the next. The question beside it is what a screen reader reads. */
@Composable
private fun SubjectFace(
    source: Any?,
    box: FaceBox?,
    modifier: Modifier = Modifier,
    size: Dp = SubjectFaceSize,
    ringed: Boolean = false,
) {
    val colors = AppColors.current
    val cropBox = remember(box) { box?.let { DetailsPeopleRow.clampedFaceBox(it) } }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.surfaceWeak)
            .then(if (ringed) Modifier.border(StackRing, colors.sheetBg, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (source != null && cropBox != null) {
            val context = LocalContext.current
            val decodePx = DetailsPeopleRow.decodePx(cropBox, with(LocalDensity.current) { size.toPx() })
            val request = remember(source, cropBox, decodePx) {
                ImageRequest.Builder(context)
                    .data(source)
                    .size(decodePx)
                    .crossfade(false)
                    .transformations(FaceCropTransformation(cropBox))
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = colors.fgMute,
                modifier = Modifier.size(size * 0.6f),
            )
        }
    }
}

private val SubjectFaceSize = 56.dp

private val StackFaceSize = 48.dp

/** How far each face of a stack sits past the one before it, so neighbours overlap by the rest. */
private val StackStep = 30.dp

private val StackRing = 2.dp
