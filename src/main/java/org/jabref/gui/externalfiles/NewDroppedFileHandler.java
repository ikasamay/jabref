package org.jabref.gui.externalfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

import org.jabref.gui.DialogService;
import org.jabref.gui.externalfiletype.ExternalFileType;
import org.jabref.gui.externalfiletype.ExternalFileTypes;
import org.jabref.gui.externalfiletype.UnknownExternalFileType;
import org.jabref.logic.cleanup.MoveFilesCleanup;
import org.jabref.logic.importer.ImportFormatPreferences;
import org.jabref.logic.importer.fileformat.PdfContentImporter;
import org.jabref.logic.l10n.Localization;
import org.jabref.logic.util.io.FileUtil;
import org.jabref.logic.xmp.XmpUtilReader;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.LinkedFile;
import org.jabref.model.metadata.FileDirectoryPreferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NewDroppedFileHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(NewDroppedFileHandler.class);

    private final BibDatabaseContext bibDatabaseContext;
    private final ExternalFileTypes externalFileTypes;
    private final FileDirectoryPreferences fileDirectoryPreferences;
    private final ImportFormatPreferences importFormatPreferences;
    private final MoveFilesCleanup moveFilesCleanup;
    private final DialogService dialogService;

    public NewDroppedFileHandler(DialogService dialogService, BibDatabaseContext bibDatabaseContext, ExternalFileTypes externalFileTypes, FileDirectoryPreferences fileDirectoryPreferences, String fileDirPattern, ImportFormatPreferences importFormatPreferences) {
        this.dialogService = dialogService;
        this.externalFileTypes = externalFileTypes;
        this.fileDirectoryPreferences = fileDirectoryPreferences;
        this.bibDatabaseContext = bibDatabaseContext;
        this.importFormatPreferences = importFormatPreferences;
        this.moveFilesCleanup = new MoveFilesCleanup(bibDatabaseContext, fileDirPattern, fileDirectoryPreferences);
    }

    public void addFilesToEntry(BibEntry entry, List<Path> files) {

        for (Path file : files) {
            FileUtil.getFileExtension(file).ifPresent(ext -> {

                ExternalFileType type = externalFileTypes.getExternalFileTypeByExt(ext)
                                                         .orElse(new UnknownExternalFileType(ext));
                Path relativePath = FileUtil.shortenFileName(file, bibDatabaseContext.getFileDirectoriesAsPaths(fileDirectoryPreferences));
                LinkedFile linkedfile = new LinkedFile("", relativePath.toString(), type.getName());
                entry.addFile(linkedfile);
            });

        }

    }

    public void addNewEntryFromXMPorPDFContent(BibEntry entry, List<Path> files) {
        PdfContentImporter pdfImporter = new PdfContentImporter(importFormatPreferences);

        for (Path file : files) {

            if (FileUtil.getFileExtension(file).filter(ext -> ext.equals("pdf")).isPresent()) {

                try {

                    List<BibEntry> result = pdfImporter.importDatabase(file, StandardCharsets.UTF_8).getDatabase().getEntries();
                    //TODO: Show Merge Dialog
                    List<BibEntry> xmpEntriesInFile = XmpUtilReader.readXmp(file, importFormatPreferences.getXmpPreferences());

                    if (xmpEntriesInFile.isEmpty()) {
                        addToEntryAndMoveToFileDir(entry, files);
                    } else {
                        showImportOrLinkFileDialog(xmpEntriesInFile, file, entry);
                    }

                } catch (IOException e) {
                    LOGGER.warn("Problem reading XMP", e);
                }

            }
        }
    }

    private void showImportOrLinkFileDialog(List<BibEntry> entriesToImport, Path fileName, BibEntry entryToLink) {

        DialogPane pane = new DialogPane();

        VBox vbox = new VBox();
        Text text = new Text(Localization.lang("The PDF contains one or several BibTeX-records.")
                             + "\n" + Localization.lang("Do you want to import these as new entries into the current library or do you want to link the file to the entry?"));
        vbox.getChildren().add(text);

        entriesToImport.forEach(entry -> {
            TextArea textArea = new TextArea(entry.toString());
            textArea.setEditable(false);
            vbox.getChildren().add(textArea);
        });
        pane.setContent(vbox);

        ButtonType importButton = new ButtonType("Import into library", ButtonData.OK_DONE);
        ButtonType linkToEntry = new ButtonType("Link file to entry", ButtonData.OTHER);

        Optional<ButtonType> buttonPressed = dialogService.showCustomDialogAndWait(Localization.lang("XMP-metadata found in PDF: %0", fileName.getFileName().toString()), pane, importButton, linkToEntry, ButtonType.CANCEL);

        if (buttonPressed.equals(Optional.of(importButton))) {
            bibDatabaseContext.getDatabase().insertEntries(entriesToImport);
        }
        if (buttonPressed.equals(Optional.of(linkToEntry))) {
            addToEntryAndMoveToFileDir(entryToLink, Arrays.asList(fileName));
        }
    }

    public void addToEntryAndMoveToFileDir(BibEntry entry, List<Path> files) {
        addFilesToEntry(entry, files);
        moveFilesCleanup.cleanup(entry);

    }

}
