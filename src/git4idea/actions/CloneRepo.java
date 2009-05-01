package git4idea.actions;
/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the License.
 *
 * Copyright 2007 Decentrix Inc
 * Copyright 2007 Aspiro AS
 * Copyright 2008 MQSoftware
 * Authors: gevession, Erlend Simonsen & Mark Scott
 *
 * This code was originally derived from the MKS & Mercurial IDEA VCS plugins
 */

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.GitVcs;
import git4idea.commands.GitCommand;
import git4idea.commands.GitCommandRunnable;
import git4idea.vfs.GitVirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Use Git to clone a repository
 */
public class CloneRepo extends BasicAction {
    @Override
    public void perform(@NotNull Project project, GitVcs vcs, @NotNull List<VcsException> exceptions,
                        @NotNull VirtualFile[] affectedFiles) throws VcsException {
        saveAll();

        final String repoURL = Messages.showInputDialog(project, "Specify repository clone URL", "clone",
                Messages.getQuestionIcon());
        if (repoURL == null || (repoURL.length() == 0))
            return;

        FileChooserDescriptor fcd = new FileChooserDescriptor(false, true, false, false, false, false);
        fcd.setShowFileSystemRoots(true);
        fcd.setTitle("Repository Parent Directory");
        fcd.setDescription("Select parent directory for clone.");
        fcd.setHideIgnored(false);
        VirtualFile[] files = FileChooser.chooseFiles(project, fcd, null);
        if (files.length != 1 || files[0] == null) {
            return;
        }

        GitCommandRunnable cmdr = new GitCommandRunnable(project, vcs.getSettings(),
                new GitVirtualFile(project, files[0].getPath()));
        cmdr.setCommand(GitCommand.CLONE_CMD);
        cmdr.setArgs(new String[]{repoURL});

        ProgressManager manager = ProgressManager.getInstance();
        manager.runProcessWithProgressSynchronously(cmdr, "Cloning repository " + repoURL, false, project);

        VcsException ex = cmdr.getException();
        if (ex != null) {
            Messages.showErrorDialog(project, ex.getMessage(), "Error occurred during 'git clone'");
            return;
        }

        VcsDirtyScopeManager mgr = VcsDirtyScopeManager.getInstance(project);
        for (VirtualFile file : affectedFiles) {
            mgr.fileDirty(file);
            file.refresh(true, true);
        }
    }

    @Override
    @NotNull
    protected String getActionName(@NotNull AbstractVcs abstractvcs) {
        return "Clone";
    }

    @Override
    protected boolean isEnabled(@NotNull Project project, @NotNull GitVcs mksvcs, @NotNull VirtualFile... vFiles) {
        return true;
    }
}