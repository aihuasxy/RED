/*
 * Copyright 2015 Nokia Solutions and Networks
 * Licensed under the Apache License, Version 2.0,
 * see license.txt file for details.
 */
package org.robotframework.ide.eclipse.main.plugin.tableeditor.keywords.handler;

import javax.inject.Named;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.robotframework.ide.eclipse.main.plugin.model.RobotKeywordCall;
import org.robotframework.ide.eclipse.main.plugin.model.RobotKeywordDefinition;
import org.robotframework.ide.eclipse.main.plugin.model.cmd.MoveKeywordCallDownCommand;
import org.robotframework.ide.eclipse.main.plugin.model.cmd.keywords.MoveKeywordDefinitionDownCommand;
import org.robotframework.ide.eclipse.main.plugin.tableeditor.RobotEditorCommandsStack;
import org.robotframework.ide.eclipse.main.plugin.tableeditor.keywords.handler.MoveKeywordsDownHandler.E4MoveKeywordDownHandler;
import org.robotframework.red.commands.DIParameterizedHandler;
import org.robotframework.red.viewers.Selections;

import com.google.common.base.Optional;

public class MoveKeywordsDownHandler extends DIParameterizedHandler<E4MoveKeywordDownHandler> {

    public MoveKeywordsDownHandler() {
        super(E4MoveKeywordDownHandler.class);
    }

    public static class E4MoveKeywordDownHandler {

        @Execute
        public void moveDown(@Named(Selections.SELECTION) final IStructuredSelection selection,
                final RobotEditorCommandsStack commandsStack) {
            final Optional<RobotKeywordCall> maybeKeywordCall = Selections.getOptionalFirstElement(selection,
                    RobotKeywordCall.class);
            final Optional<RobotKeywordDefinition> maybeKeywordDef = Selections.getOptionalFirstElement(selection,
                    RobotKeywordDefinition.class);

            if (maybeKeywordCall.isPresent()) {
                commandsStack.execute(new MoveKeywordCallDownCommand(maybeKeywordCall.get()));
            } else if (maybeKeywordDef.isPresent()) {
                commandsStack.execute(new MoveKeywordDefinitionDownCommand(maybeKeywordDef.get()));
            }
        }
    }
}
