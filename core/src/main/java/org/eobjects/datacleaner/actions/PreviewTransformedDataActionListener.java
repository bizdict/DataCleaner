/**
 * eobjects.org DataCleaner
 * Copyright (C) 2010 eobjects.org
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.eobjects.datacleaner.actions;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.concurrent.Callable;

import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableModel;

import org.eobjects.analyzer.beans.filter.MaxRowsFilter;
import org.eobjects.analyzer.beans.filter.ValidationCategory;
import org.eobjects.analyzer.configuration.AnalyzerBeansConfiguration;
import org.eobjects.analyzer.data.InputColumn;
import org.eobjects.analyzer.data.MetaModelInputColumn;
import org.eobjects.analyzer.descriptors.Descriptors;
import org.eobjects.analyzer.job.InputColumnSinkJob;
import org.eobjects.analyzer.job.InputColumnSourceJob;
import org.eobjects.analyzer.job.Outcome;
import org.eobjects.analyzer.job.OutcomeSinkJob;
import org.eobjects.analyzer.job.OutcomeSourceJob;
import org.eobjects.analyzer.job.builder.AnalysisJobBuilder;
import org.eobjects.analyzer.job.builder.FilterJobBuilder;
import org.eobjects.analyzer.job.builder.MergedOutcomeJobBuilder;
import org.eobjects.analyzer.job.builder.RowProcessingAnalyzerJobBuilder;
import org.eobjects.analyzer.job.builder.TransformerJobBuilder;
import org.eobjects.analyzer.job.runner.AnalysisResultFuture;
import org.eobjects.analyzer.job.runner.AnalysisRunner;
import org.eobjects.analyzer.job.runner.AnalysisRunnerImpl;
import org.eobjects.analyzer.result.AnalyzerResult;
import org.eobjects.analyzer.result.ListResult;
import org.eobjects.analyzer.util.SourceColumnFinder;
import org.eobjects.datacleaner.bootstrap.WindowContext;
import org.eobjects.datacleaner.panels.TransformerJobBuilderPresenter;
import org.eobjects.datacleaner.util.PreviewTransformedDataAnalyzer;
import org.eobjects.datacleaner.windows.DataSetWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ActionListener responsible for previewing transformed data in a
 * {@link DataSetWindow}.
 * 
 * @author Kasper Sørensen
 */
public final class PreviewTransformedDataActionListener implements ActionListener, Callable<TableModel> {

	private static final Logger logger = LoggerFactory.getLogger(PreviewTransformedDataActionListener.class);

	public static final int DEFAULT_PREVIEW_ROWS = 200;

	private final TransformerJobBuilderPresenter _transformerJobBuilderPresenter;
	private final AnalysisJobBuilder _analysisJobBuilder;
	private final TransformerJobBuilder<?> _transformerJobBuilder;
	private final WindowContext _windowContext;

	public PreviewTransformedDataActionListener(WindowContext windowContext,
			TransformerJobBuilderPresenter transformerJobBuilderPresenter, AnalysisJobBuilder analysisJobBuilder,
			TransformerJobBuilder<?> transformerJobBuilder, AnalyzerBeansConfiguration configuration) {
		_windowContext = windowContext;
		_transformerJobBuilderPresenter = transformerJobBuilderPresenter;
		_analysisJobBuilder = analysisJobBuilder;
		_transformerJobBuilder = transformerJobBuilder;
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		DataSetWindow window = new DataSetWindow("Preview of transformed dataset", this, _windowContext);
		window.setVisible(true);
	}

	@Override
	public TableModel call() throws Exception {
		if (_transformerJobBuilderPresenter != null) {
			_transformerJobBuilderPresenter.applyPropertyValues();
		}

		final SourceColumnFinder sourceColumnFinder = new SourceColumnFinder();

		// a copy of the current job builder, which will include only components
		// preceding the transformer to preview.
		final AnalysisJobBuilder ajb = new AnalysisJobBuilder(_analysisJobBuilder.getConfiguration());
		ajb.setDataContextProvider(_analysisJobBuilder.getDataContextProvider());

		// add a max rows filter
		final FilterJobBuilder<MaxRowsFilter, ValidationCategory> maxRowFilter = ajb.addFilter(MaxRowsFilter.class);
		maxRowFilter.getConfigurableBean().setMaxRows(DEFAULT_PREVIEW_ROWS);
		ajb.setDefaultRequirement(maxRowFilter, ValidationCategory.VALID);

		// add all components preceding the transformer
		sourceColumnFinder.addSources(_analysisJobBuilder);
		addPrecedingComponents(ajb, sourceColumnFinder, _transformerJobBuilder);

		// add the result collector (a dummy analyzer)
		final RowProcessingAnalyzerJobBuilder<PreviewTransformedDataAnalyzer> rowCollector = ajb
				.addRowProcessingAnalyzer(Descriptors.ofAnalyzer(PreviewTransformedDataAnalyzer.class))
				.addInputColumns(_transformerJobBuilder.getInputColumns())
				.addInputColumns(_transformerJobBuilder.getOutputColumns());

		final String[] columnNames = new String[rowCollector.getInputColumns().size()];
		for (int i = 0; i < columnNames.length; i++) {
			columnNames[i] = rowCollector.getInputColumns().get(i).getName();
		}

		final AnalysisRunner runner = new AnalysisRunnerImpl(ajb.getConfiguration());
		final AnalysisResultFuture resultFuture = runner.run(ajb.toAnalysisJob());

		resultFuture.await();

		if (resultFuture.isErrornous()) {
			List<Throwable> errors = resultFuture.getErrors();
			logger.error("Error occurred while running preview data job: {}", errors.get(0).getMessage());
			for (Throwable throwable : errors) {
				logger.info("Preview data error", throwable);
			}
			return null;
		}

		final List<AnalyzerResult> results = resultFuture.getResults();
		assert results.size() == 1;

		@SuppressWarnings("unchecked")
		final ListResult<Object[]> result = (ListResult<Object[]>) results.get(0);

		final List<Object[]> rows = result.getValues();
		final DefaultTableModel tableModel = new DefaultTableModel(columnNames, rows.size());
		int rowIndex = 0;
		for (Object[] row : rows) {
			for (int columnIndex = 0; columnIndex < row.length; columnIndex++) {
				tableModel.setValueAt(row[columnIndex], rowIndex, columnIndex);
			}
			rowIndex++;
		}

		return tableModel;
	}

	private void addPrecedingComponents(AnalysisJobBuilder ajb, SourceColumnFinder sourceColumnFinder,
			Object componentBuilder) {
		if (componentBuilder == null) {
			return;
		} else if (componentBuilder instanceof TransformerJobBuilder) {
			TransformerJobBuilder<?> transformerJobBuilder = (TransformerJobBuilder<?>) componentBuilder;
			ajb.addTransformer(transformerJobBuilder);
		} else if (componentBuilder instanceof FilterJobBuilder) {
			FilterJobBuilder<?, ?> filterJobBuilder = (FilterJobBuilder<?, ?>) componentBuilder;
			ajb.addFilter(filterJobBuilder);
		} else if (componentBuilder instanceof RowProcessingAnalyzerJobBuilder) {
			RowProcessingAnalyzerJobBuilder<?> rowProcessingAnalyzerJobBuilder = (RowProcessingAnalyzerJobBuilder<?>) componentBuilder;
			ajb.addRowProcessingAnalyzer(rowProcessingAnalyzerJobBuilder);
		} else if (componentBuilder instanceof MergedOutcomeJobBuilder) {
			MergedOutcomeJobBuilder mergedOutcomeJobBuilder = (MergedOutcomeJobBuilder) componentBuilder;
			ajb.addMergedOutcomeJobBuilder(mergedOutcomeJobBuilder);
		} else {
			throw new UnsupportedOperationException("Unsupported component type: " + componentBuilder.getClass());
		}

		if (componentBuilder instanceof OutcomeSinkJob) {
			OutcomeSinkJob outcomeSinkJob = (OutcomeSinkJob) componentBuilder;
			Outcome[] reqs = outcomeSinkJob.getRequirements();
			for (Outcome req : reqs) {
				OutcomeSourceJob source = sourceColumnFinder.findOutcomeSource(req);
				addPrecedingComponents(ajb, sourceColumnFinder, source);
			}
		}

		if (componentBuilder instanceof InputColumnSinkJob) {
			InputColumnSinkJob inputColumnSink = (InputColumnSinkJob) componentBuilder;
			InputColumn<?>[] inputColumns = inputColumnSink.getInput();
			for (InputColumn<?> inputColumn : inputColumns) {
				if (inputColumn.isPhysicalColumn()) {
					ajb.addSourceColumn((MetaModelInputColumn) inputColumn);
				} else {
					InputColumnSourceJob source = sourceColumnFinder.findInputColumnSource(inputColumn);
					addPrecedingComponents(ajb, sourceColumnFinder, source);
				}
			}
		}
	}
}
