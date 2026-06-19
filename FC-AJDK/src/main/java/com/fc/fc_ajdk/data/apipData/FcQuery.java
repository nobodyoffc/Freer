package com.fc.fc_ajdk.data.apipData;

import java.io.BufferedReader;
import java.util.Arrays;

public class FcQuery {

    public static final String TERMS = "terms";
    public static final String PART = "part";
    public static final String MATCH = "match";
    public static final String RANGE = "range";
    public static final String EQUALS = "equals";
    public static final String UNEQUALS = "unequals";
    public static final String EXISTS = "exists";
    public static final String UNEXISTS = "unexists";
    public static final String[] QUERY_FIELDS = new String[]{TERMS, PART, MATCH, RANGE, EQUALS, UNEQUALS, EXISTS, UNEXISTS};
    protected String[] exists;
    protected String[] unexists;
    protected Terms terms;
    protected Part part;
    protected Match match;
    protected Range range;
    protected Equals equals;
    protected Equals unequals;

    public Terms addNewTerms() {
        Terms newOne = new Terms();
        this.setTerms(newOne);
        return newOne;
    }

    public Part addNewPart() {
        Part newOne = new Part();
        this.setPart(newOne);
        return newOne;
    }

    public Match addNewMatch() {
        Match newOne = new Match();
        this.setMatch(newOne);
        return newOne;
    }

    public Range addNewRange() {
        Range newOne = new Range();
        this.setRange(newOne);
        return newOne;
    }

    public Equals addNewEquals() {
        Equals newOne = new Equals();
        this.setEquals(newOne);
        return newOne;
    }

    public Equals addNewUnequals() {
        Equals newOne = new Equals();
        this.setUnequals(newOne);
        return newOne;
    }

    public FcQuery addNewExists(String... fields) {
        this.exists = fields;
        return this;
    }

    public FcQuery appendExists(String field) {
        String[] newExists = Arrays.copyOf(exists, exists.length + 1);
        newExists[exists.length] = field;
        exists = newExists;
        return this;
    }

    public FcQuery addNewUnexists(String... fields) {
        this.unexists = fields;
        return this;
    }

    public FcQuery appendUnexists(String field) {
        String[] newUnexists = Arrays.copyOf(unexists, unexists.length + 1);
        newUnexists[unexists.length] = field;
        unexists = newUnexists;
        return this;
    }


    public String[] getExists() {
        return exists;
    }

    public void setExists(String[] exists) {
        this.exists = exists;
    }

    public String[] getUnexists() {
        return unexists;
    }

    public void setUnexists(String[] unexists) {
        this.unexists = unexists;
    }

    public Terms getTerms() {
        return terms;
    }

    public void setTerms(Terms terms) {
        this.terms = terms;
    }

    public Part getPart() {
        return part;
    }

    public void setPart(Part part) {
        this.part = part;
    }

    public Match getMatch() {
        return match;
    }

    public void setMatch(Match match) {
        this.match = match;
    }

    public Range getRange() {
        return range;
    }

    public void setRange(Range range) {
        this.range = range;
    }

    public Equals getEquals() {
        return equals;
    }

    public void setEquals(Equals equals) {
        this.equals = equals;
    }

    public Equals getUnequals() {
        return unequals;
    }

    public void setUnequals(Equals unequals) {
        this.unequals = unequals;
    }

}
