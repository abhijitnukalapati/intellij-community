/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.openapi.vcs.changes.patch;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diff.impl.patch.FilePatch;
import com.intellij.openapi.diff.impl.patch.PatchReader;
import com.intellij.openapi.diff.impl.patch.TextFilePatch;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Getter;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.SimpleContentRevision;
import com.intellij.openapi.vcs.changes.actions.ChangeDiffRequestPresentable;
import com.intellij.openapi.vcs.changes.actions.DiffRequestPresentable;
import com.intellij.openapi.vcs.changes.actions.DiffRequestPresentableProxy;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Collection;

public class TextFilePatchInProgress extends AbstractFilePatchInProgress<TextFilePatch> {

  protected TextFilePatchInProgress(TextFilePatch patch,
                                    Collection<VirtualFile> autoBases,
                                    VirtualFile baseDir) {
    super(patch.pathsOnlyCopy(), autoBases, baseDir);
  }

  public ContentRevision getNewContentRevision() {
    if (FilePatchStatus.DELETED.equals(myStatus)) return null;

    if (myNewContentRevision == null) {
      myConflicts = null;
      if (FilePatchStatus.ADDED.equals(myStatus)) {
        final FilePath newFilePath = VcsUtil.getFilePathOnNonLocal(myIoCurrentBase.getAbsolutePath(), false);
        final String content = myPatch.getNewFileText();
        myNewContentRevision = new SimpleContentRevision(content, newFilePath, myPatch.getAfterVersionId());
      }
      else {
        final FilePath newFilePath = detectNewFilePathForMovedOrModified();
        myNewContentRevision = new LazyPatchContentRevision(myCurrentBase, newFilePath, myPatch.getAfterVersionId(), myPatch);
        if (myCurrentBase != null) {
          ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
            public void run() {
              ((LazyPatchContentRevision)myNewContentRevision).getContent();
            }
          });
        }
      }
    }
    return myNewContentRevision;
  }

  @Override
  public DiffRequestPresentable getDiffRequestPresentable(final Project project, final PatchReader patchReader) {
    final PatchChange change = getChange();
    final FilePatch patch = getPatch();
    final String path = patch.getBeforeName() == null ? patch.getAfterName() : patch.getBeforeName();
    final Getter<CharSequence> baseContents = new Getter<CharSequence>() {
      @Override
      public CharSequence get() {
        return patchReader.getBaseRevision(project, path);
      }
    };
    return new DiffRequestPresentableProxy() {
      @NotNull
      @Override
      public DiffRequestPresentable init() throws VcsException {
        if (isConflictingChange()) {
          final Getter<ApplyPatchForBaseRevisionTexts> revisionTextsGetter = new Getter<ApplyPatchForBaseRevisionTexts>() {
            @Override
            public ApplyPatchForBaseRevisionTexts get() {
              final VirtualFile currentBase = getCurrentBase();
              return ApplyPatchForBaseRevisionTexts.create(project, currentBase,
                                                           VcsUtil.getFilePath(currentBase),
                                                           getPatch(), baseContents);
            }
          };
          return new MergedDiffRequestPresentable(project, revisionTextsGetter,
                                                  getCurrentBase(), getPatch().getAfterVersionId());
        }
        else {
          return new ChangeDiffRequestPresentable(project, change);
        }
      }

      @Override
      public String getPathPresentation() {
        final File ioCurrentBase = getIoCurrentBase();
        return ioCurrentBase == null ? getCurrentPath() : ioCurrentBase.getPath();
      }
    };
  }
}