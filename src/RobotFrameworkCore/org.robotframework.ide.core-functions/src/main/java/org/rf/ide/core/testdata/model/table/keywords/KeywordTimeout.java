/*
 * Copyright 2015 Nokia Solutions and Networks
 * Licensed under the Apache License, Version 2.0,
 * see license.txt file for details.
 */
package org.rf.ide.core.testdata.model.table.keywords;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.rf.ide.core.testdata.model.AModelElement;
import org.rf.ide.core.testdata.model.FilePosition;
import org.rf.ide.core.testdata.model.ICommentHolder;
import org.rf.ide.core.testdata.model.ModelType;
import org.rf.ide.core.testdata.text.read.recognizer.RobotToken;
import org.rf.ide.core.testdata.text.read.recognizer.RobotTokenType;

public class KeywordTimeout extends AModelElement<UserKeyword> implements ICommentHolder, Serializable {

    private static final long serialVersionUID = 4686638810580940346L;

    private final RobotToken declaration;

    private RobotToken timeout;

    private final List<RobotToken> message = new ArrayList<>();

    private final List<RobotToken> comment = new ArrayList<>();

    public KeywordTimeout(final RobotToken declaration) {
        this.declaration = declaration;
        fixForTheType(declaration, RobotTokenType.KEYWORD_SETTING_TIMEOUT);
    }

    @Override
    public boolean isPresent() {
        return (declaration != null);
    }

    @Override
    public RobotToken getDeclaration() {
        return declaration;
    }

    public RobotToken getTimeout() {
        return timeout;
    }

    public void setTimeout(final RobotToken timeout) {
        fixForTheType(timeout, RobotTokenType.KEYWORD_SETTING_TIMEOUT_VALUE, true);
        this.timeout = timeout;
    }

    public void setTimeout(final String timeout) {
        this.timeout = updateOrCreate(this.timeout, timeout, RobotTokenType.KEYWORD_SETTING_TIMEOUT_VALUE);
    }

    public List<RobotToken> getMessage() {
        return Collections.unmodifiableList(message);
    }

    public void addMessagePart(final RobotToken messagePart) {
        fixForTheType(messagePart, RobotTokenType.KEYWORD_SETTING_TIMEOUT_MESSAGE, true);
        this.message.add(messagePart);
    }

    public void addMessagePart(final int index, final String value) {
        updateOrCreateTokenInside(message, index, value, RobotTokenType.KEYWORD_SETTING_TIMEOUT_MESSAGE);
    }

    @Override
    public List<RobotToken> getComment() {
        return Collections.unmodifiableList(comment);
    }

    @Override
    public void addCommentPart(final RobotToken rt) {
        fixComment(getComment(), rt);
        this.comment.add(rt);
    }

    @Override
    public ModelType getModelType() {
        return ModelType.USER_KEYWORD_TIMEOUT;
    }

    @Override
    public FilePosition getBeginPosition() {
        return getDeclaration().getFilePosition();
    }

    @Override
    public void setComment(final String comment) {
        final RobotToken tok = new RobotToken();
        tok.setText(comment);
        setComment(tok);
    }

    @Override
    public void setComment(final RobotToken comment) {
        this.comment.clear();
        addCommentPart(comment);
    }

    @Override
    public void removeCommentPart(final int index) {
        this.comment.remove(index);
    }

    @Override
    public void clearComment() {
        this.comment.clear();
    }

    @Override
    public List<RobotToken> getElementTokens() {
        final List<RobotToken> tokens = new ArrayList<>();
        if (isPresent()) {
            tokens.add(getDeclaration());
            if (getTimeout() != null) {
                tokens.add(getTimeout());
            }
            tokens.addAll(getMessage());
            tokens.addAll(getComment());
        }

        return tokens;
    }

    @Override
    public boolean removeElementToken(final int index) {
        return super.removeElementFromList(message, index);
    }

    public KeywordTimeout copy() {
        final KeywordTimeout keywordTimeout = new KeywordTimeout(this.getDeclaration().copyWithoutPosition());
        keywordTimeout.setTimeout(this.getTimeout().copyWithoutPosition());
        for (final RobotToken message : getMessage()) {
            keywordTimeout.addMessagePart(message.copyWithoutPosition());
        }
        for (final RobotToken commentToken : getComment()) {
            keywordTimeout.addCommentPart(commentToken.copyWithoutPosition());
        }
        return keywordTimeout;
    }
}
