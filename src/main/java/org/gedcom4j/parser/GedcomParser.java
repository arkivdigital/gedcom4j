/* 
 * Copyright (c) 2009-2016 Matthew R. Harrah
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, 
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.gedcom4j.parser;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.gedcom4j.exception.GedcomParserException;
import org.gedcom4j.exception.ParserCancelledException;
import org.gedcom4j.exception.UnsupportedVersionException;
import org.gedcom4j.io.event.FileProgressEvent;
import org.gedcom4j.io.event.FileProgressListener;
import org.gedcom4j.io.reader.GedcomFileReader;
import org.gedcom4j.model.*;
import org.gedcom4j.parser.event.ParseProgressEvent;
import org.gedcom4j.parser.event.ParseProgressListener;

/**
 * <p>
 * Class for parsing GEDCOM 5.5 files and creating a {@link Gedcom} structure from them.
 * </p>
 * <p>
 * General usage is as follows:
 * </p>
 * <ol>
 * <li>Instantiate a <code>GedcomParser</code> object</li>
 * <li>Call the <code>GedcomParser.load()</code> method (in one of its various forms) to parse a file/stream</li>
 * <li>Access the parser's <code>gedcom</code> property to access the parsed data</li>
 * </ol>
 * <p>
 * It is <b>highly recommended</b> that after calling the <code>GedcomParser.load()</code> method, the user check the
 * {@link GedcomParser#errors} and {@link GedcomParser#warnings} collections to see if anything problematic was
 * encountered in the data while parsing. Most commonly, the <code>warnings</code> collection will have information
 * about tags from GEDCOM 5.5.1 that were specified in a file that was designated as a GEDCOM 5.5 file. When this
 * occurs, the data is loaded, but will not be able to be written by {@link org.gedcom4j.writer.GedcomWriter} until the
 * version number in the <code>gedcomVersion</code> field of {@link Gedcom#header} is updated to
 * {@link SupportedVersion#V5_5_1}, or the 5.5.1-specific data is cleared from the data.
 * </p>
 * <p>
 * The parser takes a "forgiving" approach where it tries to load as much data as possible, including 5.5.1 data in a
 * file that says it's in 5.5 format, and vice-versa. However, when it finds inconsistencies, it will add messages to
 * the warnings and errors collections. Most of these messages indicate that the data was loaded, even though it was
 * incorrect, and the data will need to be corrected before it can be written.
 * </p>
 * 
 * <p>
 * The parser makes the assumption that if the version of GEDCOM used is explicitly specified in the file header, that
 * the rest of the data in the file should conform to that spec. For example, if the file header says the file is in 5.5
 * format (i.e., has a VERS 5.5 tag), then it will generate warnings if the new 5.5.1 tags (e.g., EMAIL) are encountered
 * elsewhere, but will load the data anyway. If no version is specified, the 5.5.1 format is assumed as a default.
 * </p>
 * 
 * <p>
 * This approach was selected based on the presumption that most of the uses of GEDCOM4J will be to read GEDCOM files
 * rather than to write them, so this provides that use case with the lowest friction.
 * </p>
 * 
 * @author frizbog1
 * 
 */
public class GedcomParser {

    /**
     * The things that went wrong while parsing the gedcom file
     */
    public List<String> errors = new ArrayList<String>();

    /**
     * The content of the gedcom file
     */
    public Gedcom gedcom = new Gedcom();

    /**
     * Indicates whether handling of custom tags should be strict - that is, must an unrecognized tag begin with an
     * underscore to be loaded into the custom tags collection? If false, unrecognized tags will be treated as custom
     * tags even if they don't begin with underscores, and no errors will be issued. If true, unrecognized tags that do
     * not begin with underscores will be discarded, with errors added to the errors collection.
     */
    public boolean strictCustomTags = true;

    /**
     * Indicates whether non-compliant GEDCOM files with actual line breaks in text values (rather than CONT tags)
     * should be parsed (with some loss of data) rather than fail with an exception.
     */
    public boolean strictLineBreaks = true;

    /**
     * The warnings issued during the parsing of the gedcom file
     */
    public List<String> warnings = new ArrayList<String>();

    /**
     * Is the load/parse process being cancelled
     */
    private boolean cancelled;

    /**
     * Send a notification to listeners every time this many lines (or more) are read
     */
    private int readNotificationRate = 500;

    /**
     * The list of observers on file operations
     */
    private final List<WeakReference<FileProgressListener>> fileObservers = new CopyOnWriteArrayList<WeakReference<FileProgressListener>>();

    /**
     * The list of observers on parsing
     */
    private final List<WeakReference<ParseProgressListener>> parseObservers = new CopyOnWriteArrayList<WeakReference<ParseProgressListener>>();

    /**
     * Get a notification whenever this many items (or more) have been parsed
     */
    private int parseNotificationRate = 500;

    /**
     * Indicate that file loading should be cancelled
     */
    public void cancel() {
        cancelled = true;
    }

    /**
     * Get the parse notification rate (the number of items that get parsed between each notification, if listening)
     * 
     * @return the parse notification rate (the number of items that get parsed between each notification, if listening)
     */
    public int getParseNotificationRate() {
        return parseNotificationRate;
    }

    /**
     * Get the read notification rate
     * 
     * @return the read notification rate
     */
    public int getReadNotificationRate() {
        return readNotificationRate;
    }

    /**
     * Is the load and parse operation cancelled?
     * 
     * @return whether the load and parse operation is cancelled
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Load a gedcom file from an input stream and create an object hierarchy from the data therein.
     * 
     * @param stream
     *            the stream to load from
     * @throws IOException
     *             if the file cannot be read
     * @throws GedcomParserException
     *             if the file cannot be parsed
     */
    public void load(BufferedInputStream stream) throws IOException, GedcomParserException {
        StringTree stringTree = readStream(stream);
        if (cancelled) {
            throw new ParserCancelledException("File load/parse cancelled");
        }
        loadRootItems(stringTree);
    }

    /**
     * Load a gedcom file by filename and create an object heirarchy from the data therein.
     * 
     * @param filename
     *            the name of the file to load
     * @throws IOException
     *             if the file cannot be read
     * @throws GedcomParserException
     *             if the file cannot be parsed
     */
    public void load(String filename) throws IOException, GedcomParserException {
        StringTree stringTree = readFile(filename);
        if (cancelled) {
            throw new ParserCancelledException("File load/parse cancelled");
        }
        loadRootItems(stringTree);
    }

    /**
     * Notify all listeners about the change
     * 
     * @param e
     *            the change event to tell the observers
     */
    public void notifyFileObservers(FileProgressEvent e) {
        int i = 0;
        while (i < fileObservers.size()) {
            WeakReference<FileProgressListener> observerRef = fileObservers.get(i);
            if (observerRef == null) {
                fileObservers.remove(observerRef);
            } else {
                observerRef.get().progressNotification(e);
                i++;
            }
        }
    }

    /**
     * Register a observer (listener) to be informed about progress and completion.
     * 
     * @param observer
     *            the observer you want notified
     */
    public void registerFileObserver(FileProgressListener observer) {
        fileObservers.add(new WeakReference<FileProgressListener>(observer));
    }

    /**
     * Register a observer (listener) to be informed about progress and completion.
     * 
     * @param observer
     *            the observer you want notified
     */
    public void registerParseObserver(ParseProgressListener observer) {
        parseObservers.add(new WeakReference<ParseProgressListener>(observer));
    }

    /**
     * Set the parse notification rate (the number of items that get parsed between each notification, if listening)
     * 
     * @param parseNotificationRate
     *            the parse notification rate (the number of items that get parsed between each notification, if
     *            listening). Must be at least 1.
     */
    public void setParseNotificationRate(int parseNotificationRate) {
        if (parseNotificationRate < 1) {
            throw new IllegalArgumentException("Parse Notification Rate must be at least 1");
        }
        this.parseNotificationRate = parseNotificationRate;
    }

    /**
     * Set the read notification rate.
     * 
     * @param readNotificationRate
     *            the read notification rate. Must be a positive integer.
     */
    public void setReadNotificationRate(int readNotificationRate) {
        if (readNotificationRate < 1) {
            throw new IllegalArgumentException("Read Notification Rate must be at least 1");
        }
        this.readNotificationRate = readNotificationRate;
    }

    /**
     * Unregister a observer (listener) to be informed about progress and completion.
     * 
     * @param observer
     *            the observer you want notified
     */
    public void unregisterFileObserver(FileProgressListener observer) {
        int i = 0;
        while (i < fileObservers.size()) {
            WeakReference<FileProgressListener> observerRef = fileObservers.get(i);
            if (observerRef == null || observerRef.get() == observer) {
                fileObservers.remove(observerRef);
            } else {
                i++;
            }
        }
        fileObservers.add(new WeakReference<FileProgressListener>(observer));
    }

    /**
     * Unregister a observer (listener) to be informed about progress and completion.
     * 
     * @param observer
     *            the observer you want notified
     */
    public void unregisterParseObserver(ParseProgressListener observer) {
        int i = 0;
        while (i < parseObservers.size()) {
            WeakReference<ParseProgressListener> observerRef = parseObservers.get(i);
            if (observerRef == null || observerRef.get() == observer) {
                parseObservers.remove(observerRef);
            } else {
                i++;
            }
        }
        parseObservers.add(new WeakReference<ParseProgressListener>(observer));
    }

    /**
     * Load the flat file into a tree structure that reflects the heirarchy of its contents, using the default encoding
     * for your JVM
     * 
     * @param filename
     *            the file to load
     * @return the string tree representation of the data from the file
     * @throws IOException
     *             if there is a problem reading the file
     * @throws GedcomParserException
     *             if there is a problem parsing the data in the file
     */
    StringTree readFile(String filename) throws IOException, GedcomParserException {
        FileInputStream fis = new FileInputStream(filename);
        try {
            return makeStringTreeFromStream(new BufferedInputStream(fis));
        } finally {
            fis.close();
        }
    }

    /**
     * Returns true if and only if the Gedcom data says it is for the 5.5 standard.
     * 
     * @return true if and only if the Gedcom data says it is for the 5.5 standard.
     */
    private boolean g55() {
        return gedcom != null && gedcom.getHeader() != null && gedcom.getHeader().getGedcomVersion() != null && SupportedVersion.V5_5.equals(gedcom.getHeader()
                .getGedcomVersion().getVersionNumber());
    }

    /**
     * Get a family by their xref, adding them to the gedcom collection of families if needed.
     * 
     * @param xref
     *            the xref of the family
     * @return the family with the specified xref
     */
    private Family getFamily(String xref) {
        Family f = gedcom.getFamilies().get(xref);
        if (f == null) {
            f = new Family();
            f.setXref(xref);
            gedcom.getFamilies().put(xref, f);
        }
        return f;
    }

    /**
     * Get an individual by their xref, adding them to the gedcom collection of individuals if needed.
     * 
     * @param xref
     *            the xref of the individual
     * @return the individual with the specified xref
     */
    private Individual getIndividual(String xref) {
        Individual i;
        i = gedcom.getIndividuals().get(xref);
        if (i == null) {
            i = new Individual();
            i.setXref(xref);
            gedcom.getIndividuals().put(xref, i);
        }
        return i;
    }

    /**
     * Get a multimedia item by its xref, adding it to the gedcom collection of multimedia items if needed.
     * 
     * @param xref
     *            the xref of the multimedia item
     * @return the multimedia item with the specified xref
     */
    private Multimedia getMultimedia(String xref) {
        Multimedia m;
        m = gedcom.getMultimedia().get(xref);
        if (m == null) {
            m = new Multimedia();
            m.setXref(xref);
            gedcom.getMultimedia().put(xref, m);
        }
        return m;
    }

    /**
     * Get a note by its xref, adding it to the gedcom collection of notes if needed.
     * 
     * @param xref
     *            the xref of the note
     * @return the note with the specified xref
     */
    private Note getNote(String xref) {
        Note note;
        note = gedcom.getNotes().get(xref);
        if (note == null) {
            note = new Note();
            note.setXref(xref);
            gedcom.getNotes().put(xref, note);
        }
        return note;
    }

    /**
     * Get a repository by its xref, adding it to the gedcom collection of repositories if needed.
     * 
     * @param xref
     *            the xref of the repository
     * @return the repository with the specified xref
     */
    private Repository getRepository(String xref) {
        Repository r = gedcom.getRepositories().get(xref);
        if (r == null) {
            r = new Repository();
            r.setXref(xref);
            gedcom.getRepositories().put(xref, r);
        }
        return r;

    }

    /**
     * Get a source by its xref, adding it to the gedcom collection of sources if needed.
     * 
     * @param xref
     *            the xref of the source
     * @return the source with the specified xref
     */
    private Source getSource(String xref) {
        Source src = gedcom.getSources().get(xref);
        if (src == null) {
            src = new Source(xref);
            gedcom.getSources().put(src.getXref(), src);
        }
        return src;
    }

    /**
     * Get a submitter by their xref, adding them to the gedcom collection of submitters if needed.
     * 
     * @param xref
     *            the xref of the submitter
     * @return the submitter with the specified xref
     */
    private Submitter getSubmitter(String xref) {
        Submitter s;
        s = gedcom.getSubmitters().get(xref);
        if (s == null) {
            s = new Submitter();
            s.setName(new StringWithCustomTags("UNSPECIFIED"));
            s.setXref(xref);
            gedcom.getSubmitters().put(xref, s);
        }
        return s;
    }

    /**
     * Load an address node into an address structure
     * 
     * @param st
     *            the string tree node
     * @param address
     *            the address to load into
     */
    private void loadAddress(StringTree st, Address address) {
        if (st.getValue() != null) {
            address.getLines().add(st.getValue());
        }
        for (StringTree ch : st.getChildren()) {
            if (Tag.ADDRESS_1.equals(ch.getTag())) {
                address.setAddr1(new StringWithCustomTags(ch));
            } else if (Tag.ADDRESS_2.equals(ch.getTag())) {
                address.setAddr2(new StringWithCustomTags(ch));
            } else if (Tag.CITY.equals(ch.getTag())) {
                address.setCity(new StringWithCustomTags(ch));
            } else if (Tag.STATE.equals(ch.getTag())) {
                address.setStateProvince(new StringWithCustomTags(ch));
            } else if (Tag.POSTAL_CODE.equals(ch.getTag())) {
                address.setPostalCode(new StringWithCustomTags(ch));
            } else if (Tag.COUNTRY.equals(ch.getTag())) {
                address.setCountry(new StringWithCustomTags(ch));
            } else if (Tag.CONCATENATION.equals(ch.getTag())) {
                if (address.getLines().isEmpty()) {
                    address.getLines().add(ch.getValue());
                } else {
                    address.getLines().set(address.getLines().size() - 1, address.getLines().get(address.getLines().size() - 1) + ch.getValue());
                }
            } else if (Tag.CONTINUATION.equals(ch.getTag())) {
                address.getLines().add(ch.getValue() == null ? "" : ch.getValue());
            } else {
                unknownTag(ch, address);
            }
        }
    }

    /**
     * Load an association between two individuals from a string tree node
     * 
     * @param st
     *            the node
     * @param associations
     *            the list of associations to load into
     */
    private void loadAssociation(StringTree st, List<Association> associations) {
        Association a = new Association();
        associations.add(a);
        a.setAssociatedEntityXref(st.getValue());
        for (StringTree ch : st.getChildren()) {
            if (Tag.RELATIONSHIP.equals(ch.getTag())) {
                a.setRelationship(new StringWithCustomTags(ch));
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, a.getNotes());
            } else if (Tag.SOURCE.equals(ch.getTag())) {
                loadCitation(ch, a.getCitations());
            } else if (Tag.TYPE.equals(ch.getTag())) {
                a.setAssociatedEntityType(new StringWithCustomTags(ch));
            } else {
                unknownTag(ch, a);
            }
        }

    }

    /**
     * Load a change date structure from a string tree node
     * 
     * @param st
     *            the node
     * @param changeDate
     *            the change date to load into
     */
    private void loadChangeDate(StringTree st, ChangeDate changeDate) {
        for (StringTree ch : st.getChildren()) {
            if (Tag.DATE.equals(ch.getTag())) {
                changeDate.setDate(new StringWithCustomTags(ch.getValue()));
                if (!ch.getChildren().isEmpty()) {
                    changeDate.setTime(new StringWithCustomTags(ch.getChildren().get(0)));
                }
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, changeDate.getNotes());
            } else {
                unknownTag(ch, changeDate);
            }
        }

    }

    /**
     * Load a source citation from a string tree node into a list of citations
     * 
     * @param st
     *            the string tree node
     * @param list
     *            the list of citations to load into
     */
    private void loadCitation(StringTree st, List<AbstractCitation> list) {
        AbstractCitation citation;
        if (referencesAnotherNode(st)) {
            citation = new CitationWithSource();
            loadCitationWithSource(st, citation);
        } else {
            citation = new CitationWithoutSource();
            loadCitationWithoutSource(st, citation);
        }
        list.add(citation);
    }

    /**
     * Load a DATA structure in a source citation from a string tree node
     * 
     * @param st
     *            the node
     * @param d
     *            the CitationData structure
     */
    private void loadCitationData(StringTree st, CitationData d) {
        for (StringTree ch : st.getChildren()) {
            if (Tag.DATE.equals(ch.getTag())) {
                d.setEntryDate(new StringWithCustomTags(ch));
            } else if (Tag.TEXT.equals(ch.getTag())) {
                List<String> ls = new ArrayList<String>();
                d.getSourceText().add(ls);
                loadMultiLinesOfText(ch, ls, d);
            } else {
                unknownTag(ch, d);
            }
        }

    }

    /**
     * Load the non-cross-referenced source citation from a string tree node
     * 
     * @param st
     *            the node
     * @param citation
     *            the citation to load into
     */
    private void loadCitationWithoutSource(StringTree st, AbstractCitation citation) {
        CitationWithoutSource cws = (CitationWithoutSource) citation;
        cws.getDescription().add(st.getValue());
        for (StringTree ch : st.getChildren()) {
            if (Tag.CONTINUATION.equals(ch.getTag())) {
                cws.getDescription().add(ch.getValue() == null ? "" : ch.getValue());
            } else if (Tag.CONCATENATION.equals(ch.getTag())) {
                if (cws.getDescription().isEmpty()) {
                    cws.getDescription().add(ch.getValue());
                } else {
                    // Append to last value in string list
                    cws.getDescription().set(cws.getDescription().size() - 1, cws.getDescription().get(cws.getDescription().size() - 1) + ch.getValue());
                }
            } else if (Tag.TEXT.equals(ch.getTag())) {
                List<String> ls = new ArrayList<String>();
                cws.getTextFromSource().add(ls);
                loadMultiLinesOfText(ch, ls, cws);
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, cws.getNotes());
            } else {
                unknownTag(ch, citation);
            }
        }
    }

    /**
     * Load a cross-referenced source citation from a string tree node
     * 
     * @param st
     *            the node
     * @param citation
     *            the citation to load into
     */
    private void loadCitationWithSource(StringTree st, AbstractCitation citation) {
        CitationWithSource cws = (CitationWithSource) citation;
        Source src = null;
        if (referencesAnotherNode(st)) {
            src = getSource(st.getValue());
        }
        cws.setSource(src);
        for (StringTree ch : st.getChildren()) {
            if (Tag.PAGE.equals(ch.getTag())) {
                cws.setWhereInSource(new StringWithCustomTags(ch));
            } else if (Tag.EVENT.equals(ch.getTag())) {
                cws.setEventCited(new StringWithCustomTags(ch.getValue()));
                if (ch.getChildren() != null) {
                    for (StringTree gc : ch.getChildren()) {
                        if (Tag.ROLE.equals(gc.getTag())) {
                            cws.setRoleInEvent(new StringWithCustomTags(gc));
                        } else {
                            unknownTag(gc, cws.getEventCited());
                        }
                    }
                }
            } else if (Tag.DATA_FOR_CITATION.equals(ch.getTag())) {
                CitationData d = new CitationData();
                cws.getData().add(d);
                loadCitationData(ch, d);
            } else if (Tag.QUALITY.equals(ch.getTag())) {
                cws.setCertainty(new StringWithCustomTags(ch));
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, cws.getNotes());
            } else if (Tag.OBJECT_MULTIMEDIA.equals(ch.getTag())) {
                loadMultimediaLink(ch, cws.getMultimedia());
            } else {
                unknownTag(ch, citation);
            }
        }
    }

    /**
     * Load a corporation structure from a string tree node
     * 
     * @param st
     *            the node
     * @param corporation
     *            the corporation structure to fill
     */
    private void loadCorporation(StringTree st, Corporation corporation) {
        corporation.setBusinessName(st.getValue());
        for (StringTree ch : st.getChildren()) {
            if (Tag.ADDRESS.equals(ch.getTag())) {
                corporation.setAddress(new Address());
                loadAddress(ch, corporation.getAddress());
            } else if (Tag.PHONE.equals(ch.getTag())) {
                corporation.getPhoneNumbers().add(new StringWithCustomTags(ch));
            } else if (Tag.WEB_ADDRESS.equals(ch.getTag())) {
                corporation.getWwwUrls().add(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but WWW URL was specified for the corporation in the source system on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.FAX.equals(ch.getTag())) {
                corporation.getFaxNumbers().add(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but fax number was specified for the corporation in the source system on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.EMAIL.equals(ch.getTag())) {
                corporation.getEmails().add(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but emails was specified for the corporation in the source system on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else {
                unknownTag(ch, corporation);
            }
        }
    }

    /**
     * Load a family structure from a stringtree node, and load it into the gedcom family collection
     * 
     * @param st
     *            the node
     */
    private void loadFamily(StringTree st) {
        Family f = getFamily(st.getId());
        for (StringTree ch : st.getChildren()) {
            if (Tag.HUSBAND.equals(ch.getTag())) {
                f.setHusband(getIndividual(ch.getValue()));
            } else if (Tag.WIFE.equals(ch.getTag())) {
                f.setWife(getIndividual(ch.getValue()));
            } else if (Tag.CHILD.equals(ch.getTag())) {
                f.getChildren().add(getIndividual(ch.getValue()));
            } else if (Tag.NUM_CHILDREN.equals(ch.getTag())) {
                f.setNumChildren(new StringWithCustomTags(ch));
            } else if (Tag.SOURCE.equals(ch.getTag())) {
                loadCitation(ch, f.getCitations());
            } else if (Tag.OBJECT_MULTIMEDIA.equals(ch.getTag())) {
                loadMultimediaLink(ch, f.getMultimedia());
            } else if (Tag.RECORD_ID_NUMBER.equals(ch.getTag())) {
                f.setAutomatedRecordId(new StringWithCustomTags(ch));
            } else if (Tag.CHANGED_DATETIME.equals(ch.getTag())) {
                f.setChangeDate(new ChangeDate());
                loadChangeDate(ch, f.getChangeDate());
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, f.getNotes());
            } else if (Tag.RESTRICTION.equals(ch.getTag())) {
                f.setRestrictionNotice(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but restriction notice was specified for family on line " + ch.getLineNum()
                            + " , which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.REGISTRATION_FILE_NUMBER.equals(ch.getTag())) {
                f.setRecFileNumber(new StringWithCustomTags(ch));
            } else if (FamilyEventType.isValidTag(ch.getTag())) {
                loadFamilyEvent(ch, f.getEvents());
            } else if (Tag.SEALING_SPOUSE.equals(ch.getTag())) {
                loadLdsSpouseSealing(ch, f.getLdsSpouseSealings());
            } else if (Tag.SUBMITTER.equals(ch.getTag())) {
                f.getSubmitters().add(getSubmitter(ch.getValue()));
            } else if (Tag.REFERENCE.equals(ch.getTag())) {
                UserReference u = new UserReference();
                f.getUserReferences().add(u);
                loadUserReference(ch, u);
            } else {
                unknownTag(ch, f);
            }
        }

    }

    /**
     * Load a family event from a string tree node into a list of family events
     * 
     * @param st
     *            the node
     * @param events
     *            the list of family events
     */
    private void loadFamilyEvent(StringTree st, List<FamilyEvent> events) {
        FamilyEvent e = new FamilyEvent();
        events.add(e);
        e.setType(FamilyEventType.getFromTag(st.getTag()));
        if ("Y".equals(st.getValue())) {
            e.setyNull(st.getValue());
            e.setDescription(null);
        } else if (st.getValue() == null || st.getValue().trim().length() == 0) {
            e.setyNull(null);
            e.setDescription(null);
        } else {
            e.setyNull(null);
            e.setDescription(new StringWithCustomTags(st.getValue()));
            warnings.add(st.getTag() + " tag had description rather than [Y|<NULL>] - violates standard");
        }
        for (StringTree ch : st.getChildren()) {
            if (Tag.TYPE.equals(ch.getTag())) {
                e.setSubType(new StringWithCustomTags(ch));
            } else if (Tag.DATE.equals(ch.getTag())) {
                e.setDate(new StringWithCustomTags(ch));
            } else if (Tag.PLACE.equals(ch.getTag())) {
                e.setPlace(new Place());
                loadPlace(ch, e.getPlace());
            } else if (Tag.OBJECT_MULTIMEDIA.equals(ch.getTag())) {
                loadMultimediaLink(ch, e.getMultimedia());
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, e.getNotes());
            } else if (Tag.SOURCE.equals(ch.getTag())) {
                loadCitation(ch, e.getCitations());
            } else if (Tag.RESTRICTION.equals(ch.getTag())) {
                e.setRestrictionNotice(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but restriction notice was specified for family event on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.RELIGION.equals(ch.getTag())) {
                e.setReligiousAffiliation(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but religious affiliation was specified for family event on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.AGE.equals(ch.getTag())) {
                e.setAge(new StringWithCustomTags(ch));
            } else if (Tag.CAUSE.equals(ch.getTag())) {
                e.setCause(new StringWithCustomTags(ch));
            } else if (Tag.ADDRESS.equals(ch.getTag())) {
                e.setAddress(new Address());
                loadAddress(ch, e.getAddress());
            } else if (Tag.AGENCY.equals(ch.getTag())) {
                e.setRespAgency(new StringWithCustomTags(ch));
            } else if (Tag.PHONE.equals(ch.getTag())) {
                e.getPhoneNumbers().add(new StringWithCustomTags(ch));
            } else if (Tag.WEB_ADDRESS.equals(ch.getTag())) {
                e.getWwwUrls().add(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but WWW URL was specified for " + e.getType() + " family event on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.FAX.equals(ch.getTag())) {
                e.getFaxNumbers().add(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but fax number was specified for " + e.getType() + " family event on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.EMAIL.equals(ch.getTag())) {
                e.getEmails().add(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but email was specified for " + e.getType() + " family event on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.HUSBAND.equals(ch.getTag())) {
                e.setHusbandAge(new StringWithCustomTags(ch.getChildren().get(0)));
            } else if (Tag.WIFE.equals(ch.getTag())) {
                e.setWifeAge(new StringWithCustomTags(ch.getChildren().get(0)));
            } else if (Tag.CONCATENATION.equals(ch.getTag())) {
                if (e.getDescription() == null) {
                    e.setDescription(new StringWithCustomTags(ch));
                } else {
                    e.getDescription().setValue(e.getDescription().getValue() + ch.getValue());
                }
            } else if (Tag.CONTINUATION.equals(ch.getTag())) {
                if (e.getDescription() == null) {
                    e.setDescription(new StringWithCustomTags(ch.getValue() == null ? "" : ch.getValue()));
                } else {
                    e.getDescription().setValue(e.getDescription().getValue() + "\n" + ch.getValue());
                }
            } else {
                unknownTag(ch, e);
            }
        }

    }

    /**
     * Load a reference to a family where this individual was a child, from a string tree node
     * 
     * @param st
     *            the string tree node
     * @param familiesWhereChild
     *            the list of families where the individual was a child
     */
    private void loadFamilyWhereChild(StringTree st, List<FamilyChild> familiesWhereChild) {
        Family f = getFamily(st.getValue());
        FamilyChild fc = new FamilyChild();
        familiesWhereChild.add(fc);
        fc.setFamily(f);
        for (StringTree ch : st.getChildren()) {
            if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, fc.getNotes());
            } else if (Tag.PEDIGREE.equals(ch.getTag())) {
                fc.setPedigree(new StringWithCustomTags(ch));
            } else if (Tag.ADOPTION.equals(ch.getTag())) {
                fc.setAdoptedBy(AdoptedByWhichParent.valueOf(ch.getValue()));
            } else if (Tag.STATUS.equals(ch.getTag())) {
                fc.setStatus(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but status was specified for child-to-family link on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else {
                unknownTag(ch, fc);
            }
        }

    }

    /**
     * Load a reference to a family where this individual was a spouse, from a string tree node
     * 
     * @param st
     *            the string tree node
     * @param familiesWhereSpouse
     *            the list of families where the individual was a child
     */
    private void loadFamilyWhereSpouse(StringTree st, List<FamilySpouse> familiesWhereSpouse) {
        Family f = getFamily(st.getValue());
        FamilySpouse fs = new FamilySpouse();
        fs.setFamily(f);
        familiesWhereSpouse.add(fs);
        for (StringTree ch : st.getChildren()) {
            if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, fs.getNotes());
            } else {
                unknownTag(ch, fs);
            }
        }
    }

    /**
     * Load a single 5.5-style file reference
     * 
     * @param m
     *            The multimedia object to contain the new file reference
     * @param children
     *            the sub-tags of the OBJE tag
     */
    private void loadFileReference55(Multimedia m, List<StringTree> children) {
        FileReference currentFileRef = new FileReference();
        m.getFileReferences().add(currentFileRef);
        for (StringTree ch : children) {
            if (Tag.FORM.equals(ch.getTag())) {
                currentFileRef.setFormat(new StringWithCustomTags(ch));
            } else if (Tag.TITLE.equals(ch.getTag())) {
                m.setEmbeddedTitle(new StringWithCustomTags(ch));
            } else if (Tag.FILE.equals(ch.getTag())) {
                currentFileRef.setReferenceToFile(new StringWithCustomTags(ch));
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, m.getNotes());
            } else {
                unknownTag(ch, m);
            }
        }

    }

    /**
     * Load all the file references in the current OBJE tag
     * 
     * @param m
     *            the multimedia object being with the reference
     * @param st
     *            the OBJE node being parsed
     */
    private void loadFileReferences(Multimedia m, StringTree st) {
        int fileTagCount = 0;
        int formTagCount = 0;

        for (StringTree ch : st.getChildren()) {
            /*
             * Count up the number of files referenced for this object - GEDCOM 5.5.1 allows multiple, 5.5 only allows 1
             */
            if (Tag.FILE.equals(ch.getTag())) {
                fileTagCount++;
            }
            /*
             * Count the number of formats referenced per file - GEDCOM 5.5.1 has them as children of FILEs (so should
             * be zero), 5.5 pairs them with the single FILE tag (so should be one)
             */
            if (Tag.FORM.equals(ch.getTag())) {
                formTagCount++;
            }
        }
        if (g55()) {
            if (fileTagCount > 1) {
                warnings.add("GEDCOM version is 5.5, but multiple files referenced in multimedia reference on line " + st.getLineNum()
                        + ", which is only allowed in 5.5.1. "
                        + "Data will be loaded, but cannot be written back out unless the GEDCOM version is changed to 5.5.1");
            }
            if (formTagCount == 0) {
                warnings.add("GEDCOM version is 5.5, but there is not a FORM tag in the multimedia link on line " + st.getLineNum()
                        + ", a scenario which is only allowed in 5.5.1. "
                        + "Data will be loaded, but cannot be written back out unless the GEDCOM version is changed to 5.5.1");
            }
        }
        if (formTagCount > 1) {
            errors.add("Multiple FORM tags were found for a multimedia file reference at line " + st.getLineNum()
                    + " - this is not compliant with any GEDCOM standard - data not loaded");
            return;
        }

        if (fileTagCount > 1 || formTagCount < fileTagCount) {
            loadFileReferences551(m, st.getChildren());
        } else {
            loadFileReference55(m, st.getChildren());
        }
    }

    /**
     * Load one or more 5.5.1-style references
     * 
     * @param m
     *            the multimedia object to which we are adding the file references
     * 
     * @param children
     *            the sub-tags of the OBJE tag
     */
    private void loadFileReferences551(Multimedia m, List<StringTree> children) {
        for (StringTree ch : children) {
            if (Tag.FILE.equals(ch.getTag())) {
                FileReference fr = new FileReference();
                m.getFileReferences().add(fr);
                fr.setReferenceToFile(new StringWithCustomTags(ch));
                if (ch.getChildren().size() != 1) {
                    errors.add("Missing or multiple children nodes found under FILE node - GEDCOM 5.5.1 standard requires exactly 1 FORM node");
                }
                for (StringTree gch : ch.getChildren()) {
                    if (Tag.FORM.equals(gch.getTag())) {
                        fr.setFormat(new StringWithCustomTags(gch.getValue()));
                        for (StringTree ggch : ch.getChildren()) {
                            if (Tag.MEDIA.equals(ggch.getTag())) {
                                fr.setMediaType(new StringWithCustomTags(ggch));
                            } else {
                                unknownTag(ggch, fr);
                            }
                        }
                    } else {
                        unknownTag(gch, fr);
                    }
                }
            } else if (Tag.TITLE.equals(ch.getTag())) {
                for (FileReference fr : m.getFileReferences()) {
                    fr.setTitle(new StringWithCustomTags(ch.getTag().intern()));
                }
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, m.getNotes());
                if (!g55()) {
                    warnings.add("Gedcom version was 5.5.1, but a NOTE was found on a multimedia link on line " + ch.getLineNum()
                            + ", which is no longer supported. "
                            + "Data will be loaded, but cannot be written back out unless the GEDCOM version is changed to 5.5");
                }
            } else {
                unknownTag(ch, m);
            }
        }
    }

    /**
     * Load a gedcom version from the string tree node
     * 
     * @param st
     *            the node
     * @param gedcomVersion
     *            the gedcom version structure to load
     */
    private void loadGedcomVersion(StringTree st, GedcomVersion gedcomVersion) {
        for (StringTree ch : st.getChildren()) {
            if (Tag.VERSION.equals(ch.getTag())) {
                SupportedVersion vn = null;
                try {
                    vn = SupportedVersion.forString(ch.getValue());
                } catch (UnsupportedVersionException e) {
                    errors.add(e.getMessage());
                }
                gedcomVersion.setVersionNumber(vn);
            } else if (Tag.FORM.equals(ch.getTag())) {
                gedcomVersion.setGedcomForm(new StringWithCustomTags(ch));
            } else {
                unknownTag(ch, gedcomVersion);
            }
        }
    }

    /**
     * Load a gedcom header from a string tree node
     * 
     * @param st
     *            the node
     */
    private void loadHeader(StringTree st) {
        Header header = new Header();
        gedcom.setHeader(header);
        for (StringTree ch : st.getChildren()) {
            if (Tag.SOURCE.equals(ch.getTag())) {
                header.setSourceSystem(new SourceSystem());
                loadSourceSystem(ch, header.getSourceSystem());
            } else if (Tag.DESTINATION.equals(ch.getTag())) {
                header.setDestinationSystem(new StringWithCustomTags(ch));
            } else if (Tag.DATE.equals(ch.getTag())) {
                header.setDate(new StringWithCustomTags(ch));
                // one optional time subitem is the only possibility here
                if (!ch.getChildren().isEmpty()) {
                    header.setTime(new StringWithCustomTags(ch.getChildren().get(0)));
                }
            } else if (Tag.CHARACTER_SET.equals(ch.getTag())) {
                header.setCharacterSet(new CharacterSet());
                header.getCharacterSet().setCharacterSetName(new StringWithCustomTags(ch));
                // one optional version subitem is the only possibility here
                if (!ch.getChildren().isEmpty()) {
                    header.getCharacterSet().setVersionNum(new StringWithCustomTags(ch.getChildren().get(0)));
                }
            } else if (Tag.SUBMITTER.equals(ch.getTag())) {
                header.setSubmitter(getSubmitter(ch.getValue()));
            } else if (Tag.FILE.equals(ch.getTag())) {
                header.setFileName(new StringWithCustomTags(ch));
            } else if (Tag.GEDCOM_VERSION.equals(ch.getTag())) {
                header.setGedcomVersion(new GedcomVersion());
                loadGedcomVersion(ch, header.getGedcomVersion());
            } else if (Tag.COPYRIGHT.equals(ch.getTag())) {
                loadMultiLinesOfText(ch, header.getCopyrightData(), header);
                if (g55() && header.getCopyrightData().size() > 1) {
                    warnings.add("GEDCOM version is 5.5, but multiple lines of copyright data were specified, which is only allowed in GEDCOM 5.5.1. "
                            + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.SUBMISSION.equals(ch.getTag())) {
                if (header.getSubmission() == null) {
                    /*
                     * There can only be one SUBMISSION record per GEDCOM, and it's found at the root level, but the
                     * HEAD structure has a cross-reference to that root-level structure, so we're setting it here (if
                     * it hasn't already been loaded, which it probably isn't yet)
                     */
                    header.setSubmission(gedcom.getSubmission());
                }
            } else if (Tag.LANGUAGE.equals(ch.getTag())) {
                header.setLanguage(new StringWithCustomTags(ch));
            } else if (Tag.PLACE.equals(ch.getTag())) {
                header.setPlaceHierarchy(new StringWithCustomTags(ch.getChildren().get(0)));
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, header.getNotes());
            } else {
                unknownTag(ch, header);
            }
        }
    }

    /**
     * Load the header source data structure from a string tree node
     * 
     * @param st
     *            the node
     * @param sourceData
     *            the header source data
     */
    private void loadHeaderSourceData(StringTree st, HeaderSourceData sourceData) {
        sourceData.setName(st.getValue());
        for (StringTree ch : st.getChildren()) {
            if (Tag.DATE.equals(ch.getTag())) {
                sourceData.setPublishDate(new StringWithCustomTags(ch));
            } else if (Tag.COPYRIGHT.equals(ch.getTag())) {
                sourceData.setCopyright(new StringWithCustomTags(ch));
            } else {
                unknownTag(ch, sourceData);
            }
        }

    }

    /**
     * Load an individual from a string tree node
     * 
     * @param st
     *            the node
     */
    private void loadIndividual(StringTree st) {
        Individual i = getIndividual(st.getId());
        for (StringTree ch : st.getChildren()) {
            if (Tag.NAME.equals(ch.getTag())) {
                PersonalName pn = new PersonalName();
                i.getNames().add(pn);
                loadPersonalName(ch, pn);
            } else if (Tag.SEX.equals(ch.getTag())) {
                i.setSex(new StringWithCustomTags(ch));
            } else if (Tag.ADDRESS.equals(ch.getTag())) {
                i.setAddress(new Address());
                loadAddress(ch, i.getAddress());
            } else if (Tag.PHONE.equals(ch.getTag())) {
                i.getPhoneNumbers().add(new StringWithCustomTags(ch));
            } else if (Tag.WEB_ADDRESS.equals(ch.getTag())) {
                i.getWwwUrls().add(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but WWW URL was specified for individual " + i.getXref() + " on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.FAX.equals(ch.getTag())) {
                i.getFaxNumbers().add(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but fax was specified for individual " + i.getXref() + "on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.EMAIL.equals(ch.getTag())) {
                i.getEmails().add(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but email was specified for individual " + i.getXref() + " on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (IndividualEventType.isValidTag(ch.getTag())) {
                loadIndividualEvent(ch, i.getEvents());
            } else if (IndividualAttributeType.isValidTag(ch.getTag())) {
                loadIndividualAttribute(ch, i.getAttributes());
            } else if (LdsIndividualOrdinanceType.isValidTag(ch.getTag())) {
                loadLdsIndividualOrdinance(ch, i.getLdsIndividualOrdinances());
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, i.getNotes());
            } else if (Tag.CHANGED_DATETIME.equals(ch.getTag())) {
                i.setChangeDate(new ChangeDate());
                loadChangeDate(ch, i.getChangeDate());
            } else if (Tag.RECORD_ID_NUMBER.equals(ch.getTag())) {
                i.setRecIdNumber(new StringWithCustomTags(ch));
            } else if (Tag.REGISTRATION_FILE_NUMBER.equals(ch.getTag())) {
                i.setPermanentRecFileNumber(new StringWithCustomTags(ch));
            } else if (Tag.OBJECT_MULTIMEDIA.equals(ch.getTag())) {
                loadMultimediaLink(ch, i.getMultimedia());
            } else if (Tag.RESTRICTION.equals(ch.getTag())) {
                i.setRestrictionNotice(new StringWithCustomTags(ch));
            } else if (Tag.SOURCE.equals(ch.getTag())) {
                loadCitation(ch, i.getCitations());
            } else if (Tag.ALIAS.equals(ch.getTag())) {
                i.getAliases().add(new StringWithCustomTags(ch));
            } else if (Tag.FAMILY_WHERE_SPOUSE.equals(ch.getTag())) {
                loadFamilyWhereSpouse(ch, i.getFamiliesWhereSpouse());
            } else if (Tag.FAMILY_WHERE_CHILD.equals(ch.getTag())) {
                loadFamilyWhereChild(ch, i.getFamiliesWhereChild());
            } else if (Tag.ASSOCIATION.equals(ch.getTag())) {
                loadAssociation(ch, i.getAssociations());
            } else if (Tag.ANCESTOR_INTEREST.equals(ch.getTag())) {
                i.getAncestorInterest().add(getSubmitter(ch.getValue()));
            } else if (Tag.DESCENDANT_INTEREST.equals(ch.getTag())) {
                i.getDescendantInterest().add(getSubmitter(ch.getValue()));
            } else if (Tag.ANCESTRAL_FILE_NUMBER.equals(ch.getTag())) {
                i.setAncestralFileNumber(new StringWithCustomTags(ch));
            } else if (Tag.REFERENCE.equals(ch.getTag())) {
                UserReference u = new UserReference();
                i.getUserReferences().add(u);
                loadUserReference(ch, u);
            } else if (Tag.SUBMITTER.equals(ch.getTag())) {
                i.getSubmitters().add(getSubmitter(ch.getValue()));
            } else {
                unknownTag(ch, i);
            }
        }

    }

    /**
     * Load an attribute about an individual from a string tree node
     * 
     * @param st
     *            the node
     * @param attributes
     *            the list of individual attributes
     */
    private void loadIndividualAttribute(StringTree st, List<IndividualAttribute> attributes) {
        IndividualAttribute a = new IndividualAttribute();
        attributes.add(a);
        a.setType(IndividualAttributeType.getFromTag(st.getTag()));
        if (IndividualAttributeType.FACT.equals(a.getType()) && g55()) {
            warnings.add("FACT tag specified on a GEDCOM 5.5 file at line " + st.getLineNum() + ", but FACT was not added until 5.5.1."
                    + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
        }
        a.setDescription(new StringWithCustomTags(st.getValue()));
        for (StringTree ch : st.getChildren()) {
            if (Tag.TYPE.equals(ch.getTag())) {
                a.setSubType(new StringWithCustomTags(ch));
            } else if (Tag.DATE.equals(ch.getTag())) {
                a.setDate(new StringWithCustomTags(ch));
            } else if (Tag.PLACE.equals(ch.getTag())) {
                a.setPlace(new Place());
                loadPlace(ch, a.getPlace());
            } else if (Tag.AGE.equals(ch.getTag())) {
                a.setAge(new StringWithCustomTags(ch));
            } else if (Tag.CAUSE.equals(ch.getTag())) {
                a.setCause(new StringWithCustomTags(ch));
            } else if (Tag.SOURCE.equals(ch.getTag())) {
                loadCitation(ch, a.getCitations());
            } else if (Tag.AGENCY.equals(ch.getTag())) {
                a.setRespAgency(new StringWithCustomTags(ch));
            } else if (Tag.PHONE.equals(ch.getTag())) {
                a.getPhoneNumbers().add(new StringWithCustomTags(ch));
            } else if (Tag.WEB_ADDRESS.equals(ch.getTag())) {
                a.getWwwUrls().add(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but WWW URL was specified for " + a.getType() + " attribute on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.FAX.equals(ch.getTag())) {
                a.getFaxNumbers().add(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but fax was specified for " + a.getType() + " attribute on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.EMAIL.equals(ch.getTag())) {
                a.getEmails().add(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but email was specified for " + a.getType() + " attribute on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.ADDRESS.equals(ch.getTag())) {
                a.setAddress(new Address());
                loadAddress(ch, a.getAddress());
            } else if (Tag.OBJECT_MULTIMEDIA.equals(ch.getTag())) {
                loadMultimediaLink(ch, a.getMultimedia());
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, a.getNotes());
            } else if (Tag.CONCATENATION.equals(ch.getTag())) {
                if (a.getDescription() == null) {
                    a.setDescription(new StringWithCustomTags(ch));
                } else {
                    a.getDescription().setValue(a.getDescription().getValue() + ch.getValue());
                }
            } else {
                unknownTag(ch, a);
            }
        }
    }

    /**
     * Load an event about an individual from a string tree node
     * 
     * @param st
     *            the node
     * @param events
     *            the list of events about an individual
     */
    private void loadIndividualEvent(StringTree st, List<IndividualEvent> events) {
        IndividualEvent e = new IndividualEvent();
        events.add(e);
        e.setType(IndividualEventType.getFromTag(st.getTag()));
        if ("Y".equals(st.getValue())) {
            e.setyNull(st.getValue());
            e.setDescription(null);
        } else if (st.getValue() == null || st.getValue().trim().length() == 0) {
            e.setyNull(null);
            e.setDescription(null);
        } else {
            e.setyNull(null);
            e.setDescription(new StringWithCustomTags(st.getValue()));
            warnings.add(st.getTag() + " tag had description rather than [Y|<NULL>] - violates standard");
        }
        for (StringTree ch : st.getChildren()) {
            if (Tag.TYPE.equals(ch.getTag())) {
                e.setSubType(new StringWithCustomTags(ch));
            } else if (Tag.DATE.equals(ch.getTag())) {
                e.setDate(new StringWithCustomTags(ch));
            } else if (Tag.PLACE.equals(ch.getTag())) {
                e.setPlace(new Place());
                loadPlace(ch, e.getPlace());
            } else if (Tag.OBJECT_MULTIMEDIA.equals(ch.getTag())) {
                loadMultimediaLink(ch, e.getMultimedia());
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, e.getNotes());
            } else if (Tag.SOURCE.equals(ch.getTag())) {
                loadCitation(ch, e.getCitations());
            } else if (Tag.AGE.equals(ch.getTag())) {
                e.setAge(new StringWithCustomTags(ch));
            } else if (Tag.CAUSE.equals(ch.getTag())) {
                e.setCause(new StringWithCustomTags(ch));
            } else if (Tag.ADDRESS.equals(ch.getTag())) {
                e.setAddress(new Address());
                loadAddress(ch, e.getAddress());
            } else if (Tag.AGENCY.equals(ch.getTag())) {
                e.setRespAgency(new StringWithCustomTags(ch));
            } else if (Tag.RESTRICTION.equals(ch.getTag())) {
                e.setRestrictionNotice(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but restriction notice was specified for individual event on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.RELIGION.equals(ch.getTag())) {
                e.setReligiousAffiliation(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but religious affiliation was specified for individual event on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.PHONE.equals(ch.getTag())) {
                e.getPhoneNumbers().add(new StringWithCustomTags(ch));
            } else if (Tag.WEB_ADDRESS.equals(ch.getTag())) {
                e.getWwwUrls().add(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but WWW URL was specified on " + e.getType() + " event on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.FAX.equals(ch.getTag())) {
                e.getFaxNumbers().add(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but fax was specified on " + e.getType() + " event on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.EMAIL.equals(ch.getTag())) {
                e.getEmails().add(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but email was specified on " + e.getType() + " event on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.CONCATENATION.equals(ch.getTag())) {
                if (e.getDescription() == null) {
                    e.setDescription(new StringWithCustomTags(ch));
                } else {
                    e.getDescription().setValue(e.getDescription().getValue() + ch.getValue());
                }
            } else if (Tag.CONTINUATION.equals(ch.getTag())) {
                if (e.getDescription() == null) {
                    e.setDescription(new StringWithCustomTags(ch.getValue() == null ? "" : ch.getValue()));
                } else {
                    e.getDescription().setValue(e.getDescription().getValue() + "\n" + ch.getValue());
                }
            } else if (Tag.FAMILY_WHERE_CHILD.equals(ch.getTag())) {
                List<FamilyChild> families = new ArrayList<FamilyChild>();
                loadFamilyWhereChild(ch, families);
                if (!families.isEmpty()) {
                    e.setFamily(families.get(0));
                }
            } else {
                unknownTag(ch, e);
            }
        }

    }

    /**
     * Load an LDS individual ordinance from a string tree node
     * 
     * @param st
     *            the node
     * @param ldsIndividualOrdinances
     *            the list of LDS ordinances
     */
    private void loadLdsIndividualOrdinance(StringTree st, List<LdsIndividualOrdinance> ldsIndividualOrdinances) {
        LdsIndividualOrdinance o = new LdsIndividualOrdinance();
        ldsIndividualOrdinances.add(o);
        o.setType(LdsIndividualOrdinanceType.getFromTag(st.getTag()));
        o.setyNull(st.getValue());
        for (StringTree ch : st.getChildren()) {
            if (Tag.DATE.equals(ch.getTag())) {
                o.setDate(new StringWithCustomTags(ch));
            } else if (Tag.PLACE.equals(ch.getTag())) {
                o.setPlace(new StringWithCustomTags(ch));
            } else if (Tag.STATUS.equals(ch.getTag())) {
                o.setStatus(new StringWithCustomTags(ch));
            } else if (Tag.TEMPLE.equals(ch.getTag())) {
                o.setTemple(new StringWithCustomTags(ch));
            } else if (Tag.SOURCE.equals(ch.getTag())) {
                loadCitation(ch, o.getCitations());
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, o.getNotes());
            } else if (Tag.FAMILY_WHERE_CHILD.equals(ch.getTag())) {
                List<FamilyChild> families = new ArrayList<FamilyChild>();
                loadFamilyWhereChild(ch, families);
                if (!families.isEmpty()) {
                    o.setFamilyWhereChild(families.get(0));
                }
            } else {
                unknownTag(ch, o);
            }
        }
    }

    /**
     * Load an LDS Spouse Sealing from a string tree node
     * 
     * @param st
     *            the string tree node
     * @param ldsSpouseSealings
     *            the list of LDS spouse sealings on the family
     */
    private void loadLdsSpouseSealing(StringTree st, List<LdsSpouseSealing> ldsSpouseSealings) {
        LdsSpouseSealing o = new LdsSpouseSealing();
        ldsSpouseSealings.add(o);
        for (StringTree ch : st.getChildren()) {
            if (Tag.DATE.equals(ch.getTag())) {
                o.setDate(new StringWithCustomTags(ch));
            } else if (Tag.PLACE.equals(ch.getTag())) {
                o.setPlace(new StringWithCustomTags(ch));
            } else if (Tag.STATUS.equals(ch.getTag())) {
                o.setStatus(new StringWithCustomTags(ch));
            } else if (Tag.TEMPLE.equals(ch.getTag())) {
                o.setTemple(new StringWithCustomTags(ch));
            } else if (Tag.SOURCE.equals(ch.getTag())) {
                loadCitation(ch, o.getCitations());
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, o.getNotes());
            } else {
                unknownTag(ch, o);
            }

        }

    }

    /**
     * Load multiple (continued) lines of text from a string tree node
     * 
     * @param st
     *            the node
     * @param listOfString
     *            the list of string to load into
     * @param element
     *            the parent element to which the <code>listOfString</code> belongs
     */
    private void loadMultiLinesOfText(StringTree st, List<String> listOfString, AbstractElement element) {
        if (st.getValue() != null) {
            listOfString.add(st.getValue());
        }
        for (StringTree ch : st.getChildren()) {
            if (Tag.CONTINUATION.equals(ch.getTag())) {
                if (ch.getValue() == null) {
                    listOfString.add("");
                } else {
                    listOfString.add(ch.getValue());
                }
            } else if (Tag.CONCATENATION.equals(ch.getTag())) {
                // If there's no value to concatenate, ignore it
                if (ch.getValue() != null) {
                    if (listOfString.isEmpty()) {
                        listOfString.add(ch.getValue());
                    } else {
                        listOfString.set(listOfString.size() - 1, listOfString.get(listOfString.size() - 1) + ch.getValue());
                    }
                }
            } else {
                unknownTag(ch, element);
            }
        }
    }

    /**
     * Load a multimedia reference from a string tree node. This corresponds to the MULTIMEDIA_LINK structure in the
     * GEDCOM specs.
     * 
     * @param st
     *            the string tree node
     * @param multimedia
     *            the list of multimedia on the current object
     */
    private void loadMultimediaLink(StringTree st, List<Multimedia> multimedia) {
        Multimedia m;
        if (referencesAnotherNode(st)) {
            m = getMultimedia(st.getValue());
        } else {
            m = new Multimedia();
            loadFileReferences(m, st);
        }
        multimedia.add(m);
    }

    /**
     * Determine which style is being used here - GEDCOM 5.5 or 5.5.1 - and load appropriately. Warn if the structure is
     * inconsistent with the specified format.
     * 
     * @param st
     *            the OBJE node being loaded
     */
    private void loadMultimediaRecord(StringTree st) {
        int fileTagCount = 0;
        for (StringTree ch : st.getChildren()) {
            if (Tag.FILE.equals(ch.getTag())) {
                fileTagCount++;
            }
        }
        if (fileTagCount > 0) {
            if (g55()) {
                warnings.add("GEDCOM version was 5.5, but a 5.5.1-style multimedia record was found at line " + st.getLineNum() + ". "
                        + "Data will be loaded, but might have problems being written until the version is for the data is changed to 5.5.1");
            }
            loadMultimediaRecord551(st);
        } else {
            if (!g55()) {
                warnings.add("GEDCOM version is 5.5.1, but a 5.5-style multimedia record was found at line " + st.getLineNum() + ". "
                        + "Data will be loaded, but might have problems being written until the version is for the data is changed to 5.5.1");
            }
            loadMultimediaRecord55(st);
        }
    }

    /**
     * Load a GEDCOM 5.5-style multimedia record (that could be referenced from another object) from a string tree node.
     * This corresponds to the MULTIMEDIA_RECORD structure in the GEDCOM 5.5 spec.
     * 
     * @param st
     *            the OBJE node being loaded
     */
    private void loadMultimediaRecord55(StringTree st) {
        Multimedia m = getMultimedia(st.getId());
        for (StringTree ch : st.getChildren()) {
            if (Tag.FORM.equals(ch.getTag())) {
                m.setEmbeddedMediaFormat(new StringWithCustomTags(ch));
            } else if (Tag.TITLE.equals(ch.getTag())) {
                m.setEmbeddedTitle(new StringWithCustomTags(ch));
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, m.getNotes());
            } else if (Tag.SOURCE.equals(ch.getTag())) {
                loadCitation(ch, m.getCitations());
            } else if (Tag.BLOB.equals(ch.getTag())) {
                loadMultiLinesOfText(ch, m.getBlob(), m);
                if (!g55()) {
                    warnings.add("GEDCOM version is 5.5.1, but a BLOB tag was found at line " + ch.getLineNum() + ". "
                            + "Data will be loaded but will not be writeable unless GEDCOM version is changed to 5.5.1");
                }
            } else if (Tag.OBJECT_MULTIMEDIA.equals(ch.getTag())) {
                List<Multimedia> continuedObjects = new ArrayList<Multimedia>();
                loadMultimediaLink(ch, continuedObjects);
                m.setContinuedObject(continuedObjects.get(0));
                if (!g55()) {
                    warnings.add("GEDCOM version is 5.5.1, but a chained OBJE tag was found at line " + ch.getLineNum() + ". "
                            + "Data will be loaded but will not be writeable unless GEDCOM version is changed to 5.5.1");
                }
            } else if (Tag.REFERENCE.equals(ch.getTag())) {
                UserReference u = new UserReference();
                m.getUserReferences().add(u);
                loadUserReference(ch, u);
            } else if (Tag.RECORD_ID_NUMBER.equals(ch.getTag())) {
                m.setRecIdNumber(new StringWithCustomTags(ch));
            } else if (Tag.CHANGED_DATETIME.equals(ch.getTag())) {
                m.setChangeDate(new ChangeDate());
                loadChangeDate(ch, m.getChangeDate());
            } else {
                unknownTag(ch, m);
            }
        }

    }

    /**
     * Load a GEDCOM 5.5.1-style multimedia record (that could be referenced from another object) from a string tree
     * node. This corresponds to the MULTIMEDIA_RECORD structure in the GEDCOM 5.5.1 spec.
     * 
     * @param st
     *            the OBJE node being loaded
     */
    private void loadMultimediaRecord551(StringTree st) {
        Multimedia m = getMultimedia(st.getId());
        for (StringTree ch : st.getChildren()) {
            if (Tag.FILE.equals(ch.getTag())) {
                FileReference fr = new FileReference();
                m.getFileReferences().add(fr);
                fr.setReferenceToFile(new StringWithCustomTags(ch));
                for (StringTree gch : ch.getChildren()) {
                    if (Tag.FORM.equals(gch.getTag())) {
                        fr.setFormat(new StringWithCustomTags(gch.getValue()));
                        if (gch.getChildren().size() == 1) {
                            StringTree ggch = gch.getChildren().get(0);
                            if (Tag.TYPE.equals(ggch.getTag())) {
                                fr.setMediaType(new StringWithCustomTags(ggch));
                            } else {
                                unknownTag(ggch, fr);
                            }
                        }
                    } else if (Tag.TITLE.equals(gch.getTag())) {
                        fr.setTitle(new StringWithCustomTags(gch));
                    } else {
                        unknownTag(gch, fr);
                    }
                }
                if (fr.getFormat() == null) {
                    errors.add("FORM tag not found under FILE reference on line " + st.getLineNum());
                }
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, m.getNotes());
            } else if (Tag.SOURCE.equals(ch.getTag())) {
                loadCitation(ch, m.getCitations());
            } else if (Tag.REFERENCE.equals(ch.getTag())) {
                UserReference u = new UserReference();
                m.getUserReferences().add(u);
                loadUserReference(ch, u);
            } else if (Tag.RECORD_ID_NUMBER.equals(ch.getTag())) {
                m.setRecIdNumber(new StringWithCustomTags(ch));
            } else if (Tag.CHANGED_DATETIME.equals(ch.getTag())) {
                m.setChangeDate(new ChangeDate());
                loadChangeDate(ch, m.getChangeDate());
            } else {
                unknownTag(ch, m);
            }

        }

    }

    /**
     * Load a note from a string tree node into a list of notes
     * 
     * @param st
     *            the node
     * @param notes
     *            the list of notes to add the note to as it is parsed.
     */
    private void loadNote(StringTree st, List<Note> notes) {
        Note note;
        if (st.getId() == null && referencesAnotherNode(st)) {
            note = getNote(st.getValue());
            notes.add(note);
            return;
        } else if (st.getId() == null) {
            note = new Note();
            notes.add(note);
        } else {
            if (referencesAnotherNode(st)) {
                warnings.add("NOTE line has both an XREF_ID (" + st.getId() + ") and SUBMITTER_TEXT (" + st.getValue()
                        + ") value between @ signs - treating SUBMITTER_TEXT as string, not a cross-reference");
            }
            note = getNote(st.getId());
        }
        note.getLines().add(st.getValue());
        for (StringTree ch : st.getChildren()) {
            if (Tag.CONCATENATION.equals(ch.getTag())) {
                if (note.getLines().isEmpty()) {
                    note.getLines().add(ch.getValue());
                } else {
                    String lastNote = note.getLines().get(note.getLines().size() - 1);
                    if (lastNote == null || lastNote.length() == 0) {
                        note.getLines().set(note.getLines().size() - 1, ch.getValue());
                    } else {
                        note.getLines().set(note.getLines().size() - 1, lastNote + ch.getValue());
                    }
                }
            } else if (Tag.CONTINUATION.equals(ch.getTag())) {
                note.getLines().add(ch.getValue() == null ? "" : ch.getValue());
            } else if (Tag.SOURCE.equals(ch.getTag())) {
                loadCitation(ch, note.getCitations());
            } else if (Tag.REFERENCE.equals(ch.getTag())) {
                UserReference u = new UserReference();
                note.getUserReferences().add(u);
                loadUserReference(ch, u);
            } else if (Tag.RECORD_ID_NUMBER.equals(ch.getTag())) {
                note.setRecIdNumber(new StringWithCustomTags(ch));
            } else if (Tag.CHANGED_DATETIME.equals(ch.getTag())) {
                note.setChangeDate(new ChangeDate());
                loadChangeDate(ch, note.getChangeDate());
            } else {
                unknownTag(ch, note);
            }
        }
    }

    /**
     * Load a personal name structure from a string tree node
     * 
     * @param st
     *            the node
     * @param pn
     *            the personal name structure to fill in
     */
    private void loadPersonalName(StringTree st, PersonalName pn) {
        pn.setBasic(st.getValue());
        for (StringTree ch : st.getChildren()) {
            if (Tag.NAME_PREFIX.equals(ch.getTag())) {
                pn.setPrefix(new StringWithCustomTags(ch));
            } else if (Tag.GIVEN_NAME.equals(ch.getTag())) {
                pn.setGivenName(new StringWithCustomTags(ch));
            } else if (Tag.NICKNAME.equals(ch.getTag())) {
                pn.setNickname(new StringWithCustomTags(ch));
            } else if (Tag.SURNAME_PREFIX.equals(ch.getTag())) {
                pn.setSurnamePrefix(new StringWithCustomTags(ch));
            } else if (Tag.SURNAME.equals(ch.getTag())) {
                pn.setSurname(new StringWithCustomTags(ch));
            } else if (Tag.NAME_SUFFIX.equals(ch.getTag())) {
                pn.setSuffix(new StringWithCustomTags(ch));
            } else if (Tag.SOURCE.equals(ch.getTag())) {
                loadCitation(ch, pn.getCitations());
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, pn.getNotes());
            } else if (Tag.ROMANIZED.equals(ch.getTag())) {
                PersonalNameVariation pnv = new PersonalNameVariation();
                pn.getRomanized().add(pnv);
                loadPersonalNameVariation(ch, pnv);
            } else if (Tag.PHONETIC.equals(ch.getTag())) {
                PersonalNameVariation pnv = new PersonalNameVariation();
                pn.getPhonetic().add(pnv);
                loadPersonalNameVariation(ch, pnv);
            } else {
                unknownTag(ch, pn);
            }
        }

    }

    /**
     * Load a personal name variation (romanization or phonetic version) from a string tree node
     * 
     * @param st
     *            the string tree node to load from
     * @param pnv
     *            the personal name variation to fill in
     */
    private void loadPersonalNameVariation(StringTree st, PersonalNameVariation pnv) {
        pnv.setVariation(st.getValue());
        for (StringTree ch : st.getChildren()) {
            if (Tag.NAME_PREFIX.equals(ch.getTag())) {
                pnv.setPrefix(new StringWithCustomTags(ch));
            } else if (Tag.GIVEN_NAME.equals(ch.getTag())) {
                pnv.setGivenName(new StringWithCustomTags(ch));
            } else if (Tag.NICKNAME.equals(ch.getTag())) {
                pnv.setNickname(new StringWithCustomTags(ch));
            } else if (Tag.SURNAME_PREFIX.equals(ch.getTag())) {
                pnv.setSurnamePrefix(new StringWithCustomTags(ch));
            } else if (Tag.SURNAME.equals(ch.getTag())) {
                pnv.setSurname(new StringWithCustomTags(ch));
            } else if (Tag.NAME_SUFFIX.equals(ch.getTag())) {
                pnv.setSuffix(new StringWithCustomTags(ch));
            } else if (Tag.SOURCE.equals(ch.getTag())) {
                loadCitation(ch, pnv.getCitations());
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, pnv.getNotes());
            } else if (Tag.TYPE.equals(ch.getTag())) {
                pnv.setVariationType(new StringWithCustomTags(ch));
            } else {
                unknownTag(ch, pnv);
            }
        }
    }

    /**
     * Load a place structure from a string tree node
     * 
     * @param st
     *            the node
     * @param place
     *            the place structure to fill in
     */
    private void loadPlace(StringTree st, Place place) {
        place.setPlaceName(st.getValue());
        for (StringTree ch : st.getChildren()) {
            if (Tag.FORM.equals(ch.getTag())) {
                place.setPlaceFormat(new StringWithCustomTags(ch));
            } else if (Tag.SOURCE.equals(ch.getTag())) {
                loadCitation(ch, place.getCitations());
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, place.getNotes());
            } else if (Tag.CONCATENATION.equals(ch.getTag())) {
                place.setPlaceName(place.getPlaceName() + (ch.getValue() == null ? "" : ch.getValue()));
            } else if (Tag.CONTINUATION.equals(ch.getTag())) {
                place.setPlaceName(place.getPlaceName() + "\n" + (ch.getValue() == null ? "" : ch.getValue()));
            } else if (Tag.ROMANIZED.equals(ch.getTag())) {
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but a romanized variation was specified on a place on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
                NameVariation nv = new NameVariation();
                place.getRomanized().add(nv);
                nv.setVariation(ch.getValue());
                for (StringTree gch : ch.getChildren()) {
                    if (Tag.TYPE.equals(gch.getTag())) {
                        nv.setVariationType(new StringWithCustomTags(gch));
                    } else {
                        unknownTag(gch, nv);
                    }
                }
            } else if (Tag.PHONETIC.equals(ch.getTag())) {
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but a phonetic variation was specified on a place on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
                NameVariation nv = new NameVariation();
                place.getPhonetic().add(nv);
                nv.setVariation(ch.getValue());
                for (StringTree gch : ch.getChildren()) {
                    if (Tag.TYPE.equals(gch.getTag())) {
                        nv.setVariationType(new StringWithCustomTags(gch));
                    } else {
                        unknownTag(gch, nv);
                    }
                }
            } else if (Tag.MAP.equals(ch.getTag())) {
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but a map coordinate was specified on a place on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
                for (StringTree gch : ch.getChildren()) {
                    if (Tag.LATITUDE.equals(gch.getTag())) {
                        place.setLatitude(new StringWithCustomTags(gch));
                    } else if (Tag.LONGITUDE.equals(gch.getTag())) {
                        place.setLongitude(new StringWithCustomTags(gch));
                    } else {
                        unknownTag(gch, place);
                    }
                }
            } else {
                unknownTag(ch, place);
            }
        }

    }

    /**
     * Load a repository for sources from a string tree node, and put it in the gedcom collection of repositories
     * 
     * @param st
     *            the node
     */
    private void loadRepository(StringTree st) {
        Repository r = getRepository(st.getId());
        for (StringTree ch : st.getChildren()) {
            if (Tag.NAME.equals(ch.getTag())) {
                r.setName(new StringWithCustomTags(ch));
            } else if (Tag.ADDRESS.equals(ch.getTag())) {
                r.setAddress(new Address());
                loadAddress(ch, r.getAddress());
            } else if (Tag.PHONE.equals(ch.getTag())) {
                r.getPhoneNumbers().add(new StringWithCustomTags(ch));
            } else if (Tag.WEB_ADDRESS.equals(ch.getTag())) {
                r.getWwwUrls().add(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but WWW URL was specified on repository " + r.getXref() + " on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.FAX.equals(ch.getTag())) {
                r.getFaxNumbers().add(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but fax was specified on repository " + r.getXref() + " on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.EMAIL.equals(ch.getTag())) {
                r.getEmails().add(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but email was specified on repository " + r.getXref() + " on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, r.getNotes());
            } else if (Tag.REFERENCE.equals(ch.getTag())) {
                UserReference u = new UserReference();
                r.getUserReferences().add(u);
                loadUserReference(ch, u);
            } else if (Tag.RECORD_ID_NUMBER.equals(ch.getTag())) {
                r.setRecIdNumber(new StringWithCustomTags(ch));
            } else if (Tag.CHANGED_DATETIME.equals(ch.getTag())) {
                r.setChangeDate(new ChangeDate());
                loadChangeDate(ch, r.getChangeDate());
            } else {
                unknownTag(ch, r);
            }
        }

    }

    /**
     * Load a reference to a repository in a source, from a string tree node
     * 
     * @param st
     *            the node
     * @return the RepositoryCitation loaded
     */
    private RepositoryCitation loadRepositoryCitation(StringTree st) {
        RepositoryCitation r = new RepositoryCitation();
        r.setRepositoryXref(st.getValue());
        for (StringTree ch : st.getChildren()) {
            if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, r.getNotes());
            } else if (Tag.CALL_NUMBER.equals(ch.getTag())) {
                SourceCallNumber scn = new SourceCallNumber();
                r.getCallNumbers().add(scn);
                scn.setCallNumber(new StringWithCustomTags(ch.getValue()));
                for (StringTree gch : ch.getChildren()) {
                    if (Tag.MEDIA.equals(gch.getTag())) {
                        scn.setMediaType(new StringWithCustomTags(gch));
                    } else {
                        unknownTag(gch, scn.getCallNumber());
                    }
                }
            } else {
                unknownTag(ch, r);
            }
        }
        return r;
    }

    /**
     * Load the root level items for the gedcom
     * 
     * @param st
     *            the root of the string tree
     * @throws GedcomParserException
     *             if the data cannot be parsed because it's not in the format expected
     */
    private void loadRootItems(StringTree st) throws GedcomParserException {
        if (cancelled) {
            throw new ParserCancelledException("File load/parse cancelled");
        }
        int i = 0;
        for (StringTree ch : st.getChildren()) {
            if (Tag.HEADER.equals(ch.getTag())) {
                loadHeader(ch);
            } else if (Tag.SUBMITTER.equals(ch.getTag())) {
                loadSubmitter(ch);
            } else if (Tag.INDIVIDUAL.equals(ch.getTag())) {
                loadIndividual(ch);
            } else if (Tag.SUBMISSION.equals(ch.getTag())) {
                loadSubmission(ch);
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadRootNote(ch);
            } else if (Tag.FAMILY.equals(ch.getTag())) {
                loadFamily(ch);
            } else if (Tag.TRAILER.equals(ch.getTag())) {
                gedcom.setTrailer(new Trailer());
            } else if (Tag.SOURCE.equals(ch.getTag())) {
                loadSource(ch);
            } else if (Tag.REPOSITORY.equals(ch.getTag())) {
                loadRepository(ch);
            } else if (Tag.OBJECT_MULTIMEDIA.equals(ch.getTag())) {
                loadMultimediaRecord(ch);
            } else {
                unknownTag(ch, gedcom);
            }
            if (i % parseNotificationRate == 0) {
                notifyParseObservers(new ParseProgressEvent(this, gedcom, false));
            }
            if (cancelled) {
                throw new ParserCancelledException("File load/parse cancelled");
            }
        }
        notifyParseObservers(new ParseProgressEvent(this, gedcom, true));
    }

    /**
     * Load a note at the root level of the GEDCOM. All these should have &#64;ID&#64;'s and thus should get added to
     * the GEDCOM's collection of notes rather than the one passed to <code>loadNote()</code>
     * 
     * @param ch
     *            the child nodes to be loaded as a note
     * @throws GedcomParserException
     *             if the data cannot be parsed because it's not in the format expected
     */
    private void loadRootNote(StringTree ch) throws GedcomParserException {
        List<Note> dummyList = new ArrayList<Note>();
        loadNote(ch, dummyList);
        if (!dummyList.isEmpty()) {
            throw new GedcomParserException("At root level NOTE structures should have @ID@'s");
        }
    }

    /**
     * Load a source (which may be referenced later) from a source tree node, and put it in the gedcom collection of
     * sources.
     * 
     * @param st
     *            the node
     */
    private void loadSource(StringTree st) {
        Source s = getSource(st.getId());
        for (StringTree ch : st.getChildren()) {
            if (Tag.DATA_FOR_SOURCE.equals(ch.getTag())) {
                s.setData(new SourceData());
                loadSourceData(ch, s.getData());
            } else if (Tag.TITLE.equals(ch.getTag())) {
                loadMultiLinesOfText(ch, s.getTitle(), s);
            } else if (Tag.PUBLICATION_FACTS.equals(ch.getTag())) {
                loadMultiLinesOfText(ch, s.getPublicationFacts(), s);
            } else if (Tag.TEXT.equals(ch.getTag())) {
                loadMultiLinesOfText(ch, s.getSourceText(), s);
            } else if (Tag.ABBREVIATION.equals(ch.getTag())) {
                s.setSourceFiledBy(new StringWithCustomTags(ch));
            } else if (Tag.AUTHORS.equals(ch.getTag())) {
                loadMultiLinesOfText(ch, s.getOriginatorsAuthors(), s);
            } else if (Tag.REPOSITORY.equals(ch.getTag())) {
                s.setRepositoryCitation(loadRepositoryCitation(ch));
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, s.getNotes());
            } else if (Tag.OBJECT_MULTIMEDIA.equals(ch.getTag())) {
                loadMultimediaLink(ch, s.getMultimedia());
            } else if (Tag.REFERENCE.equals(ch.getTag())) {
                UserReference u = new UserReference();
                s.getUserReferences().add(u);
                loadUserReference(ch, u);
            } else if (Tag.RECORD_ID_NUMBER.equals(ch.getTag())) {
                s.setRecIdNumber(new StringWithCustomTags(ch));
            } else if (Tag.CHANGED_DATETIME.equals(ch.getTag())) {
                s.setChangeDate(new ChangeDate());
                loadChangeDate(ch, s.getChangeDate());
            } else {
                unknownTag(ch, s);
            }
        }
    }

    /**
     * Load data for a source from a string tree node into a source data structure
     * 
     * @param st
     *            the node
     * @param data
     *            the source data structure
     */
    private void loadSourceData(StringTree st, SourceData data) {
        for (StringTree ch : st.getChildren()) {
            if (Tag.EVENT.equals(ch.getTag())) {
                loadSourceDataEventRecorded(ch, data);
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, data.getNotes());
            } else if (Tag.AGENCY.equals(ch.getTag())) {
                data.setRespAgency(new StringWithCustomTags(ch));
            } else {
                unknownTag(ch, data);
            }
        }
    }

    /**
     * Load the data for a recorded event from a string tree node
     * 
     * @param st
     *            the node
     * @param data
     *            the source data
     */
    private void loadSourceDataEventRecorded(StringTree st, SourceData data) {
        EventRecorded e = new EventRecorded();
        data.getEventsRecorded().add(e);
        e.setEventType(st.getValue());
        for (StringTree ch : st.getChildren()) {
            if (Tag.DATE.equals(ch.getTag())) {
                e.setDatePeriod(new StringWithCustomTags(ch));
            } else if (Tag.PLACE.equals(ch.getTag())) {
                e.setJurisdiction(new StringWithCustomTags(ch));
            } else {
                unknownTag(ch, data);
            }
        }

    }

    /**
     * Load a source system structure from a string tree node
     * 
     * @param st
     *            the node
     * @param sourceSystem
     *            the source system structure
     */
    private void loadSourceSystem(StringTree st, SourceSystem sourceSystem) {
        sourceSystem.setSystemId(st.getValue());
        for (StringTree ch : st.getChildren()) {
            if (Tag.VERSION.equals(ch.getTag())) {
                sourceSystem.setVersionNum(new StringWithCustomTags(ch));
            } else if (Tag.NAME.equals(ch.getTag())) {
                sourceSystem.setProductName(new StringWithCustomTags(ch));
            } else if (Tag.CORPORATION.equals(ch.getTag())) {
                sourceSystem.setCorporation(new Corporation());
                loadCorporation(ch, sourceSystem.getCorporation());
            } else if (Tag.DATA_FOR_CITATION.equals(ch.getTag())) {
                sourceSystem.setSourceData(new HeaderSourceData());
                loadHeaderSourceData(ch, sourceSystem.getSourceData());
            } else {
                unknownTag(ch, sourceSystem);
            }
        }
    }

    /**
     * Load the submission structure from a string tree node
     * 
     * @param st
     *            the node
     */
    private void loadSubmission(StringTree st) {
        Submission s = new Submission(st.getId());
        gedcom.setSubmission(s);
        if (gedcom.getHeader() == null) {
            gedcom.setHeader(new Header());
        }
        if (gedcom.getHeader().getSubmission() == null) {
            /*
             * The GEDCOM spec puts a cross reference to the root-level SUBN element in the HEAD structure. Now that we
             * have a submission object, represent that cross reference in the header object
             */
            gedcom.getHeader().setSubmission(s);
        }
        for (StringTree ch : st.getChildren()) {
            Submission submission = gedcom.getSubmission();
            if (Tag.SUBMITTER.equals(ch.getTag())) {
                submission.setSubmitter(getSubmitter(ch.getValue()));
            } else if (Tag.FAMILY_FILE.equals(ch.getTag())) {
                submission.setNameOfFamilyFile(new StringWithCustomTags(ch));
            } else if (Tag.TEMPLE.equals(ch.getTag())) {
                submission.setTempleCode(new StringWithCustomTags(ch));
            } else if (Tag.ANCESTORS.equals(ch.getTag())) {
                submission.setAncestorsCount(new StringWithCustomTags(ch));
            } else if (Tag.DESCENDANTS.equals(ch.getTag())) {
                submission.setDescendantsCount(new StringWithCustomTags(ch));
            } else if (Tag.ORDINANCE_PROCESS_FLAG.equals(ch.getTag())) {
                submission.setOrdinanceProcessFlag(new StringWithCustomTags(ch));
            } else if (Tag.RECORD_ID_NUMBER.equals(ch.getTag())) {
                submission.setRecIdNumber(new StringWithCustomTags(ch));
            } else {
                unknownTag(ch, submission);
            }
        }

    }

    /**
     * Load a submitter from a string tree node into the gedcom global collection of submitters
     * 
     * @param st
     *            the node
     */
    private void loadSubmitter(StringTree st) {
        Submitter submitter = getSubmitter(st.getId());
        for (StringTree ch : st.getChildren()) {
            if (Tag.NAME.equals(ch.getTag())) {
                submitter.setName(new StringWithCustomTags(ch));
            } else if (Tag.ADDRESS.equals(ch.getTag())) {
                submitter.setAddress(new Address());
                loadAddress(ch, submitter.getAddress());
            } else if (Tag.PHONE.equals(ch.getTag())) {
                submitter.getPhoneNumbers().add(new StringWithCustomTags(ch));
            } else if (Tag.WEB_ADDRESS.equals(ch.getTag())) {
                submitter.getWwwUrls().add(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but WWW URL number was specified on submitter on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.FAX.equals(ch.getTag())) {
                submitter.getFaxNumbers().add(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but fax number was specified on submitter on line " + ch.getLineNum()
                            + ", which is a GEDCOM 5.5.1 feature." + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.EMAIL.equals(ch.getTag())) {
                submitter.getEmails().add(new StringWithCustomTags(ch));
                if (g55()) {
                    warnings.add("GEDCOM version is 5.5 but email was specified on submitter on line " + ch.getLineNum() + ", which is a GEDCOM 5.5.1 feature."
                            + "  Data loaded but cannot be re-written unless GEDCOM version changes.");
                }
            } else if (Tag.LANGUAGE.equals(ch.getTag())) {
                submitter.getLanguagePref().add(new StringWithCustomTags(ch));
            } else if (Tag.CHANGED_DATETIME.equals(ch.getTag())) {
                submitter.setChangeDate(new ChangeDate());
                loadChangeDate(ch, submitter.getChangeDate());
            } else if (Tag.OBJECT_MULTIMEDIA.equals(ch.getTag())) {
                loadMultimediaLink(ch, submitter.getMultimedia());
            } else if (Tag.RECORD_ID_NUMBER.equals(ch.getTag())) {
                submitter.setRecIdNumber(new StringWithCustomTags(ch));
            } else if (Tag.REGISTRATION_FILE_NUMBER.equals(ch.getTag())) {
                submitter.setRegFileNumber(new StringWithCustomTags(ch));
            } else if (Tag.NOTE.equals(ch.getTag())) {
                loadNote(ch, submitter.getNotes());
            } else {
                unknownTag(ch, submitter);
            }
        }
    }

    /**
     * Load a user reference to from a string tree node
     * 
     * @param st
     *            the string tree node
     * @param u
     *            the user reference object
     */
    private void loadUserReference(StringTree st, UserReference u) {
        u.setReferenceNum(st.getValue());
        if (!st.getChildren().isEmpty()) {
            u.setType(st.getChildren().get(0).getValue());
        }

    }

    /**
     * Read data from an {@link java.io.InputStream} and construct a {@link StringTree} object from its contents
     * 
     * @param bytes
     *            the input stream over the bytes of the file
     * @return the {@link StringTree} created from the contents of the input stream
     * @throws IOException
     *             if there is a problem reading the data from the reader
     * @throws GedcomParserException
     *             if there is an error with parsing the data from the stream
     */
    private StringTree makeStringTreeFromStream(BufferedInputStream bytes) throws IOException, GedcomParserException {
        /* This was the old way - loaded entire file into arraylist of strings */
        /*
         * List<String> lines = new GedcomFileReader(this, bytes).getLines(); return new StringTreeBuilder(this,
         * bytes).makeStringTreeFromFlatLines(lines);
         */
        /*
         * This is the new way - reads line at a time and adds each one to StringTree, avoiding temp arraylist of
         * strings
         */
        GedcomFileReader gfr = new GedcomFileReader(this, bytes);
        StringTreeBuilder stb = new StringTreeBuilder(this);
        String line = gfr.nextLine();
        while (line != null) {
            stb.appendLine(line);
            line = gfr.nextLine();
            if (cancelled) {
                throw new ParserCancelledException("File load/parse is cancelled");
            }
        }
        return stb.getTree();
    }

    /**
     * Notify all listeners about the change
     * 
     * @param e
     *            the change event to tell the observers
     */
    private void notifyParseObservers(ParseProgressEvent e) {
        int i = 0;
        while (i < parseObservers.size()) {
            WeakReference<ParseProgressListener> observerRef = parseObservers.get(i);
            if (observerRef == null) {
                parseObservers.remove(observerRef);
            } else {
                observerRef.get().progressNotification(e);
                i++;
            }
        }
    }

    /**
     * Read all the data from a stream and return the StringTree representation of that data
     * 
     * @param stream
     *            the stream to read
     * @return the data from the stream as a StringTree
     * @throws IOException
     *             if there's a problem reading the data off the stream
     * @throws GedcomParserException
     *             if there is an error parsing the gedcom data
     */
    private StringTree readStream(BufferedInputStream stream) throws IOException, GedcomParserException {
        return makeStringTreeFromStream(stream);
    }

    /**
     * Returns true if the node passed in uses a cross-reference to another node
     * 
     * @param st
     *            the node
     * @return true if and only if the node passed in uses a cross-reference to another node
     */
    private boolean referencesAnotherNode(StringTree st) {
        if (st.getValue() == null) {
            return false;
        }
        int r1 = st.getValue().indexOf('@');
        if (r1 == -1) {
            return false;
        }
        int r2 = st.getValue().indexOf('@', r1);
        if (r2 == -1) {
            return false;
        }
        return true;
    }

    /**
     * <p>
     * Default handler for a tag that the parser was not expecting to see.
     * </p>
     * <ul>
     * <li>If the tag begins with an underscore, it is a user-defined tag, which is stored in the customTags collection
     * of the passed in element, and returns.</li>
     * <li>If {@link #strictCustomTags} parsing is turned off (i.e., == false), it is treated as a user-defined tag
     * (despite the lack of beginning underscore) and treated like any other user-defined tag.</li>
     * <li>If {@link #strictCustomTags} parsing is turned on (i.e., == true), it is treated as bad tag and an error is
     * logged in the {@link #errors} collection.</li>
     * </ul>
     * 
     * @param node
     *            the node containing the unknown tag.
     * @param element
     *            the element that the node is part of, so if it's a custom tag, this unknown tag can be added to this
     *            node's collection of custom tags
     */
    private void unknownTag(StringTree node, AbstractElement element) {
        if (node.getTag().length() > 0 && (node.getTag().charAt(0) == '_') || !strictCustomTags) {
            element.getCustomTags().add(node);
            return;
        }

        StringBuilder sb = new StringBuilder(64); // Min size = 64
        sb.append("Line ").append(node.getLineNum()).append(": Cannot handle tag ");
        sb.append(node.getTag());
        StringTree st = node;
        while (st.getParent() != null) {
            st = st.getParent();
            sb.append(", child of ").append(st.getTag() == null ? null : st.getTag());
            if (st.getId() != null) {
                sb.append(" ").append(st.getId());
            }
            sb.append(" on line ").append(st.getLineNum());
        }
        errors.add(sb.toString());
    }

}
