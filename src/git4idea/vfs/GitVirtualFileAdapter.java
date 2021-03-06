package git4idea.vfs;
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
 * Copyright 2008 MQSoftware
 * Authors: Mark Scott
 *
 */

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.AbstractVcsHelper;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.VcsShowConfirmationOption;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.LocalFileOperationsHandler;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileAdapter;
import com.intellij.openapi.vfs.VirtualFileCopyEvent;
import com.intellij.openapi.vfs.VirtualFileEvent;
import com.intellij.openapi.vfs.VirtualFileMoveEvent;
import com.intellij.openapi.vfs.VirtualFilePropertyEvent;
import com.intellij.vcsUtil.VcsUtil;
import git4idea.commands.GitCommand;
import git4idea.vfs.GitVirtualFile;
import git4idea.GitVcs;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Git virtual file adapter
 */
public class GitVirtualFileAdapter extends VirtualFileAdapter implements LocalFileOperationsHandler {
    private Project project;
    private GitVcs vcs;
    private static final String ADD_TITLE = "Add file";
    private static final String ADD_MESSAGE = "Add file(s) to Git?\n{0}";
    private static final String DEL_TITLE = "Delete file";
    private static final String DEL_MESSAGE = "Delete file(s) in Git?\n{0}";
    private Set<String> ignoreFiles = new HashSet<String>();
    private Set<String> gitFiles = new HashSet<String>();
    private Set<String> nonGitFiles = new HashSet<String>();

    public GitVirtualFileAdapter(@NotNull GitVcs vcs, @NotNull Project project) {
        this.vcs = vcs;
        this.project = project;
    }

    @Override
    public void beforeContentsChange(@NotNull VirtualFileEvent event) {
    }

    @Override
    public void contentsChanged(@NotNull VirtualFileEvent event) {  // keep Git repo in sync
        if (event.isFromRefresh())
            return;

        final VirtualFile file = event.getFile();
        if (!isGitControlled(file))
            return;

        VirtualFile vcsRoot = VcsUtil.getVcsRootFor(project, file);
        if(vcsRoot == null) return;
        GitCommand command = new GitCommand(project, vcs.getSettings(), vcsRoot);
        try {
            command.add(new VirtualFile[]{event.getFile()});
        }
        catch (VcsException e) {
            List<VcsException> es = new ArrayList<VcsException>();
            es.add(e);
            GitVcs.getInstance(project).showErrors(es, "Error syncing changes to Git index!");
        }
        statusChange(file);
    }

    @Override
    public void fileCreated(@NotNull VirtualFileEvent event) {
        if (event.isFromRefresh())
            return;

        final VirtualFile file = event.getFile();
        if(!VcsUtil.isFileForVcs(file, project, vcs)) return;

        List<VirtualFile> files = new ArrayList<VirtualFile>();
        files.add(file);
        Collection<VirtualFile> filesToAdd = new ArrayList<VirtualFile>(1);
        VcsShowConfirmationOption option = vcs.getAddConfirmation();
        VcsShowConfirmationOption.Value userOpt = option.getValue();

        switch (userOpt) {
            case SHOW_CONFIRMATION:
                AbstractVcsHelper helper = AbstractVcsHelper.getInstance(project);
                filesToAdd = helper.selectFilesToProcess(files, ADD_TITLE, null, ADD_TITLE, ADD_MESSAGE, option);
                break;
            case DO_ACTION_SILENTLY:
                filesToAdd.add(file);
                break;
            case DO_NOTHING_SILENTLY:
                return;
        }

        if (filesToAdd == null || filesToAdd.size() == 0) {
            gitControlFile(file, true);
            return;
        } else {
            gitControlFile(file, false);
        }

        VirtualFile vcsRoot = VcsUtil.getVcsRootFor(project, file);
        if (filesToAdd != null && filesToAdd.size() > 0 && vcsRoot != null) {
            GitCommand command = new GitCommand(project, vcs.getSettings(), vcsRoot);
            try {
                command.add(filesToAdd.toArray(new VirtualFile[filesToAdd.size()]));
            }
            catch (VcsException e) {
                List<VcsException> es = new ArrayList<VcsException>();
                es.add(e);
                GitVcs.getInstance(project).showErrors(es, "Error adding file");
            }
        }

        statusChange(file);
    }

    @Override
    public void fileCopied(@NotNull VirtualFileCopyEvent event) {
        fileCreated(event);
    }

    @Override
    public void beforeFileDeletion(@NotNull VirtualFileEvent event) {
        if (event.isFromRefresh())
            return;

        final VirtualFile file = event.getFile();
        if (!isGitControlled(file))
            return;

        List<VirtualFile> files = new ArrayList<VirtualFile>();
        files.add(file);
        Collection<VirtualFile> filesToDelete = new ArrayList<VirtualFile>(1);
        VcsShowConfirmationOption option = vcs.getDeleteConfirmation();
        VcsShowConfirmationOption.Value userOpt = option.getValue();

        switch (userOpt) {
            case SHOW_CONFIRMATION:
                AbstractVcsHelper helper = AbstractVcsHelper.getInstance(project);
                filesToDelete = helper.selectFilesToProcess(files, DEL_TITLE, null, DEL_TITLE, DEL_MESSAGE, option);
                break;
            case DO_ACTION_SILENTLY:
                filesToDelete.add(file);
                break;
            case DO_NOTHING_SILENTLY:
                return;
        }

        VirtualFile vcsRoot = VcsUtil.getVcsRootFor(project, file);
        if (filesToDelete != null && filesToDelete.size() > 0 && vcsRoot != null) {
            GitCommand command = new GitCommand(project, vcs.getSettings(), vcsRoot);
            try {
                command.delete(filesToDelete.toArray(new VirtualFile[filesToDelete.size()]));
            }
            catch (VcsException e) {
                List<VcsException> es = new ArrayList<VcsException>();
                es.add(e);
                GitVcs.getInstance(project).showErrors(es, "Error deleting file");
            }
        }
    }

    @Override
    public void fileDeleted(@NotNull VirtualFileEvent event) {
        statusChange(event.getFile());
    }

    @Override
    public void beforeFileMovement(@NotNull VirtualFileMoveEvent event) {
        if (event.isFromRefresh())
            return;

        final VirtualFile file = event.getFile();
        if (!isGitControlled(file))
            return;

        VirtualFile vcsRoot = VcsUtil.getVcsRootFor(project, file);
        if(vcsRoot == null) return;
        GitCommand command = new GitCommand(project, vcs.getSettings(), vcsRoot);
        try {
            String oldPath = event.getOldParent().getPath();
            String newPath = event.getNewParent().getPath();
            String fileName = event.getFileName();
            VirtualFile ovf = new GitVirtualFile(project, oldPath + "/" + fileName, GitVirtualFile.Status.DELETED);
            VirtualFile nvf = new GitVirtualFile(project, newPath + "/" + fileName, GitVirtualFile.Status.ADDED);
            command.move(ovf, nvf);
        }
        catch (VcsException e) {
            List<VcsException> es = new ArrayList<VcsException>();
            es.add(e);
            GitVcs.getInstance(project).showErrors(es, "Error moving file");
        }
    }

    @Override
    public void fileMoved(@NotNull VirtualFileMoveEvent event) {
        statusChange(event.getOldParent());
        statusChange(event.getNewParent());
    }

    @Override
    public void propertyChanged(@NotNull VirtualFilePropertyEvent event) {  // do nothing
    }

    @Override
    public void beforePropertyChange(@NotNull VirtualFilePropertyEvent event) {
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // LocalOperationsHandler
    public boolean delete(VirtualFile file) throws IOException {
        return false;
    }

    public boolean move(VirtualFile file, VirtualFile toDir) throws IOException {
        return true;
    }

    @Nullable
    public File copy(VirtualFile file, VirtualFile toDir, String copyName) throws IOException {
        return null;
    }

    public boolean rename(VirtualFile file, String newName) throws IOException {
        if (newName == null) return false;
        if (!VcsUtil.isPathUnderProject(project, file)) return false;

        VirtualFile vcsRoot = VcsUtil.getVcsRootFor(project, file);
        GitVcs vcs = (GitVcs) VcsUtil.getVcsFor(project, file);
        if (vcs == null || vcsRoot == null) return false;
        GitCommand command = new GitCommand(project, vcs.getSettings(), vcsRoot);
        try {
            String parent = file.getParent().getPath();
            GitVirtualFile newFile = new GitVirtualFile(project,parent + "/" + newName);
            command.move(file, newFile);
            return true;
        } catch (VcsException ve) {
            throw new IOException("Error renaming file!\n" + ve.getMessage(), ve);
        }
    }

    public boolean createFile(VirtualFile dir, String name) throws IOException {
        return false;
    }

    public boolean createDirectory(VirtualFile dir, String name) throws IOException {
        return false;
    }


    /**
     * Specify whether or not Git is controlling the given files.
     * @param files  The files to ignore/not-ignore
     * @param control  true if the files should be controlled by Git, else false
     */
    public void gitControlFiles(@NotNull VirtualFile[] files, boolean control) {
        if(files.length == 0) return;
        for (VirtualFile file : files) {
            if (file != null)
                gitControlFile(file, control);
        }
    }

    /**
     * Specify whether or not Git is controlling the given file.
     * @param file The file to ignore/not-ignore
     * @param control  true if the file is controlled by Git, else false
     */
    public void gitControlFile(@NotNull VirtualFile file, boolean control) {
        if (control) {
            ignoreFiles.remove(file.getPath());
            nonGitFiles.remove(file.getPath());
            gitFiles.add(file.getPath());
        } else {
            ignoreFiles.add(file.getPath());
        }
    }

     /**
     * Query if Git should ignore a file.
     *
     * @param file The file to query
     * @return true if git is ignoring the file, else false
     */
    public boolean fileIsIgnored(@NotNull VirtualFile file) {
        isGitControlled(file);
        return ignoreFiles.contains(file.getPath());
    }

    /** Returns the current list of ignored files for this project. */
    public Set<String> getIgnoredFiles() {
        return ignoreFiles;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Private methods

    /**
     * File is not processable if it is outside the vcs scope or it is in the
     * list of excluded project files.
     *
     * @param file The file to check.
     * @return Returns true of the file can be added.
     */
    public boolean isGitControlled(@NotNull VirtualFile file) {
        if (gitFiles.contains(file.getPath())) return true;
        if (nonGitFiles.contains(file.getPath())) return false;
        if (ignoreFiles.contains(file.getPath())) return false;

        if (file.isDirectory() && file.getName().equals(".git")) {
            ignoreFiles.add(file.getPath());
            return false;
        }
        if (file.getUrl().contains("/.git/")){
            ignoreFiles.add(file.getPath());
            return false;
        }

        VirtualFile vcsRoot = VcsUtil.getVcsRootFor(project, file);
        if(vcsRoot == null) {
            nonGitFiles.add(file.getPath());
            return false;
        }

        GitCommand command = new GitCommand(project, vcs.getSettings(), vcsRoot);
        try {
            if(command.status(file)) {
                gitFiles.add(file.getPath());
                return true;
            } else {
                nonGitFiles.add(file.getPath());
                return false;
            }
        } catch (VcsException e) {
            return false;
        }
    }

    private void statusChange(@NotNull VirtualFile file) {
        if (file.isDirectory())
            VcsDirtyScopeManager.getInstance(project).dirDirtyRecursively(file);
        else
            VcsDirtyScopeManager.getInstance(project).fileDirty(file);
    }
}