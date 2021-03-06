/*
 * Copyright (C) 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.gapid.views;

import static com.google.gapid.util.Loadable.MessageType.Error;
import static com.google.gapid.util.Loadable.MessageType.Info;
import static com.google.gapid.util.Logging.throttleLogRpcError;
import static com.google.gapid.widgets.Widgets.createBoldLabel;
import static com.google.gapid.widgets.Widgets.createComposite;
import static com.google.gapid.widgets.Widgets.createGroup;
import static com.google.gapid.widgets.Widgets.createLabel;
import static com.google.gapid.widgets.Widgets.createLink;
import static com.google.gapid.widgets.Widgets.createScrolledComposite;
import static com.google.gapid.widgets.Widgets.createStandardTabFolder;
import static com.google.gapid.widgets.Widgets.createStandardTabItem;
import static com.google.gapid.widgets.Widgets.createTableColumn;
import static com.google.gapid.widgets.Widgets.createTableViewer;
import static com.google.gapid.widgets.Widgets.disposeAllChildren;
import static com.google.gapid.widgets.Widgets.packColumns;
import static com.google.gapid.widgets.Widgets.withLayoutData;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.gapid.lang.glsl.GlslSourceConfiguration;
import com.google.gapid.models.Capture;
import com.google.gapid.models.CommandStream;
import com.google.gapid.models.CommandStream.CommandIndex;
import com.google.gapid.models.Models;
import com.google.gapid.proto.service.api.API;
import com.google.gapid.proto.service.path.Path;
import com.google.gapid.rpc.Rpc;
import com.google.gapid.rpc.RpcException;
import com.google.gapid.rpc.UiErrorCallback;
import com.google.gapid.util.Loadable;
import com.google.gapid.util.Messages;
import com.google.gapid.widgets.LoadablePanel;
import com.google.gapid.widgets.Theme;
import com.google.gapid.widgets.Widgets;

import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ST;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

/**
 * View the displays the information for each stage of the pipeline.
 */
public class PipelineView extends Composite
implements Tab, Capture.Listener, CommandStream.Listener {
  protected static final Logger LOG = Logger.getLogger(PipelineView.class.getName());

  protected final Models models;
  protected final LoadablePanel<Composite> loading;
  private final Theme theme;
  protected final Composite stagesContainer;

  public PipelineView(Composite parent, Models models, Widgets widgets) {
    super(parent, SWT.NONE);
    this.models = models;
    this.theme = widgets.theme;

    setLayout(new FillLayout());

    loading = LoadablePanel.create(this, widgets,
        panel -> createComposite(panel, new FillLayout(SWT.VERTICAL)));

    stagesContainer = createComposite(loading.getContents(), new FillLayout());

    models.commands.addListener(this);
    addListener(SWT.Dispose, e -> {
      models.commands.removeListener(this);
    });
  }

  @Override
  public void reinitialize() {
    updatePipelines();
  }

  @Override
  public void onCaptureLoadingStart(boolean maintainState) {
    loading.showMessage(Info, Messages.LOADING_CAPTURE);
  }

  @Override
  public void onCaptureLoaded(Loadable.Message error) {
    if (error != null) {
      loading.showMessage(Error, Messages.CAPTURE_LOAD_FAILURE);
    }
  }

  @Override
  public void onCommandsLoaded() {
    if (!models.commands.isLoaded()) {
      loading.showMessage(Info, Messages.CAPTURE_LOAD_FAILURE);
    } else {
      if (models.commands.getSelectedCommands() == null) {
        loading.showMessage(Info, Messages.SELECT_COMMAND);
      } else {
        loading.stopLoading();
      }
    }
  }

  @Override
  public void onCommandsSelected(CommandIndex path) {
    updatePipelines();
  }

  private void updatePipelines() {
    loading.startLoading();
    Rpc.listen(models.resources.loadBoundPipelines(),
        new UiErrorCallback<API.MultiResourceData, List<API.Pipeline>, Loadable.Message>(this, LOG) {
      @Override
      protected ResultOrError<List<API.Pipeline>, Loadable.Message> onRpcThread(
          Rpc.Result<API.MultiResourceData> result) {
        try {
          List<API.Pipeline> pipelines = Lists.newArrayList();
          for (API.ResourceData resource : result.get().getResourcesList()) {
            if (resource.hasPipeline()) {
              pipelines.add(resource.getPipeline());
            }
          }
          return success(pipelines);
        } catch (RpcException e) {
          models.analytics.reportException(e);
          return error(Loadable.Message.error(e));
        } catch (ExecutionException e) {
          models.analytics.reportException(e);
          throttleLogRpcError(LOG, "Failed to load pipelines", e);
          return error(Loadable.Message.error(e.getCause().getMessage()));
        }
      }

      @Override
      protected void onUiThreadSuccess(List<API.Pipeline> pipelines) {
        setPipelines(pipelines);
      }

      @Override
      protected void onUiThreadError(Loadable.Message error) {
        loading.showMessage(error);
      }
    });
  }

  protected void setPipelines(List<API.Pipeline> pipelines) {
    loading.stopLoading();
    disposeAllChildren(stagesContainer);
    TabFolder folder = createStandardTabFolder(stagesContainer);
    createPipelineTabs(folder, pipelines);
    stagesContainer.requestLayout();
  }

  private void createPipelineTabs(TabFolder folder, List<API.Pipeline> pipelines) {
    if (!pipelines.isEmpty()) {
      for (API.Pipeline result : pipelines) {
        List<API.Stage> stages = result.getStagesList();

        for (API.Stage stage : stages) {
          TabItem item = createStandardTabItem(folder, stage.getDebugName());

          Group stageGroup = createGroup(folder, stage.getStageName());

          item.setControl(stageGroup);

          if (!stage.getEnabled()) {
            continue;
          }

          for (API.DataGroup dataGroup : stage.getGroupsList()) {
            Group dataComposite = createGroup(stageGroup, dataGroup.getGroupName());

            switch (dataGroup.getDataCase()) {
              case KEY_VALUES:
                RowLayout rowLayout = new RowLayout();
                rowLayout.wrap = true;
                ScrolledComposite scrollComposite = createScrolledComposite(dataComposite,
                    new FillLayout(), SWT.V_SCROLL | SWT.H_SCROLL);

                Composite contentComposite = createComposite(scrollComposite, rowLayout);

                List<API.KeyValuePair> kvpList = dataGroup.getKeyValues().getKeyValuesList();

                boolean dynamicExists = false;

                for (API.KeyValuePair kvp : kvpList) {
                  Composite kvpComposite =
                      createComposite(contentComposite, new GridLayout(2, false));

                  withLayoutData(
                      createBoldLabel(kvpComposite, kvp.getName() + (kvp.getDynamic() ? "*:" : ":")),
                      new GridData(SWT.BEGINNING, SWT.TOP, false, false));

                  if (!dynamicExists && kvp.getDynamic()) {
                    dataComposite.setText(dataGroup.getGroupName() + " (* value set dynamically)");
                    dynamicExists = true;
                  }

                  DataValue dv = convertDataValue(kvp.getValue());

                  if (dv.link != null) {
                    withLayoutData(createLink(
                        kvpComposite, dv.displayValue, e -> models.follower.onFollow(dv.link)),
                        new GridData(SWT.BEGINNING, SWT.TOP, false, false));
                  } else {
                    withLayoutData(createLabel(kvpComposite, dv.displayValue),
                        new GridData(SWT.BEGINNING, SWT.TOP, false, false));
                  }
                }

                scrollComposite.setContent(contentComposite);
                scrollComposite.setExpandVertical(true);
                scrollComposite.setExpandHorizontal(true);
                scrollComposite.addListener(SWT.Resize, event -> {
                  int width = scrollComposite.getClientArea().width;
                  scrollComposite.setMinHeight(contentComposite.computeSize(width, SWT.DEFAULT).y);
                });

                break;

              case TABLE:
                if (dataGroup.getTable().getDynamic()) {
                  dataComposite.setText(dataGroup.getGroupName() + " (table was set dynamically)");
                }

                TableViewer groupTable =
                    createTableViewer(dataComposite, SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL);
                List<API.Row> rows = dataGroup.getTable().getRowsList();

                groupTable.setContentProvider(ArrayContentProvider.getInstance());

                for (int i = 0; i < dataGroup.getTable().getHeadersCount(); i++) {
                  int col = i;
                  createTableColumn(groupTable, dataGroup.getTable().getHeaders(i), row -> {
                    return convertDataValue(((API.Row)row).getRowValues(col)).displayValue;
                  });
                }

                groupTable.setInput(rows);
                packColumns(groupTable.getTable());

                break;

              case SHADER:
                SourceViewer viewer = new SourceViewer(
                    dataComposite, null, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
                StyledText textWidget = viewer.getTextWidget();
                textWidget.setFont(theme.monoSpaceFont());
                textWidget.setKeyBinding(ST.SELECT_ALL, ST.SELECT_ALL);
                viewer.configure(new GlslSourceConfiguration(theme));
                viewer.setEditable(false);
                viewer.setDocument(
                    GlslSourceConfiguration.createDocument(dataGroup.getShader().getSource()));

                break;

              case DATA_NOT_SET:
                // Ignore;
                break;
            }
          }

          stageGroup.requestLayout();
        }
      }
    }
  }

  @Override
  public Control getControl() {
    return this;
  }

  private static DataValue convertDataValue(API.DataValue val) {
    switch (val.getValCase()) {
      case VALUE:
        Joiner valueJoiner = Joiner.on(", ");
        ArrayList<String> values = new ArrayList<String>();

        switch (val.getValue().getValCase()) {
          case FLOAT32:
            return new DataValue(Float.toString(val.getValue().getFloat32()));

          case FLOAT64:
            return new DataValue(Double.toString(val.getValue().getFloat64()));

          case UINT:
            return new DataValue(Long.toString(val.getValue().getUint()));

          case SINT:
            return new DataValue(Long.toString(val.getValue().getSint()));

          case UINT8:
            return new DataValue(Integer.toString(val.getValue().getUint8()));

          case SINT8:
            return new DataValue(Integer.toString(val.getValue().getSint8()));

          case UINT16:
            return new DataValue(Integer.toString(val.getValue().getUint16()));

          case SINT16:
            return new DataValue(Integer.toString(val.getValue().getSint16()));

          case UINT32:
            return new DataValue(Integer.toString(val.getValue().getUint32()));

          case SINT32:
            return new DataValue(Integer.toString(val.getValue().getSint32()));

          case UINT64:
            return new DataValue(Long.toString(val.getValue().getUint64()));

          case SINT64:
            return new DataValue(Long.toString(val.getValue().getSint64()));

          case BOOL:
            return new DataValue(Boolean.toString(val.getValue().getBool()));

          case STRING:
            return new DataValue(val.getValue().getString());

          case FLOAT32_ARRAY:
            for (float value : val.getValue().getFloat32Array().getValList()) {
              values.add(Float.toString(value));
            }
            return new DataValue(valueJoiner.join(values));

          case FLOAT64_ARRAY:
            for (double value : val.getValue().getFloat64Array().getValList()) {
              values.add(Double.toString(value));
            }
            return new DataValue(valueJoiner.join(values));

          case UINT_ARRAY:
            for (long value : val.getValue().getUintArray().getValList()) {
              values.add(Long.toString(value));
            }
            return new DataValue(valueJoiner.join(values));

          case SINT_ARRAY:
            for (long value : val.getValue().getSintArray().getValList()) {
              values.add(Long.toString(value));
            }
            return new DataValue(valueJoiner.join(values));

          case UINT8_ARRAY:
            for (byte value : val.getValue().getUint8Array()) {
              values.add(Byte.toString(value));
            }
            return new DataValue(valueJoiner.join(values));

          case SINT8_ARRAY:
            for (int value : val.getValue().getSint8Array().getValList()) {
              values.add(Integer.toString(value));
            }
            return new DataValue(valueJoiner.join(values));

          case UINT16_ARRAY:
            for (int value : val.getValue().getUint16Array().getValList()) {
              values.add(Integer.toString(value));
            }
            return new DataValue(valueJoiner.join(values));

          case SINT16_ARRAY:
            for (int value : val.getValue().getSint16Array().getValList()) {
              values.add(Integer.toString(value));
            }
            return new DataValue(valueJoiner.join(values));

          case UINT32_ARRAY:
            for (int value : val.getValue().getUint32Array().getValList()) {
              values.add(Integer.toString(value));
            }
            return new DataValue(valueJoiner.join(values));

          case SINT32_ARRAY:
            for (int value : val.getValue().getSint32Array().getValList()) {
              values.add(Integer.toString(value));
            }
            return new DataValue(valueJoiner.join(values));

          case UINT64_ARRAY:
            for (long value : val.getValue().getUint64Array().getValList()) {
              values.add(Long.toString(value));
            }
            return new DataValue(valueJoiner.join(values));

          case SINT64_ARRAY:
            for (long value : val.getValue().getSint64Array().getValList()) {
              values.add(Long.toString(value));
            }
            return new DataValue(valueJoiner.join(values));

          case BOOL_ARRAY:
            for (boolean value : val.getValue().getBoolArray().getValList()) {
              values.add(Boolean.toString(value));
            }
            return new DataValue(valueJoiner.join(values));

          case STRING_ARRAY:
            return new DataValue(valueJoiner.join(val.getValue().getStringArray().getValList()));

          default:
            return new DataValue("???");
        }

      case ENUMVAL:
        return new DataValue(val.getEnumVal().getDisplayValue());

      case BITFIELD:
        Joiner joiner = Joiner.on((val.getBitfield().getCombined()) ? "" : " | ");
        return new DataValue(joiner.join(val.getBitfield().getSetDisplayNamesList()));

      case LINK:
        DataValue dv = convertDataValue(val.getLink().getDisplayVal());
        dv.displayValue = "<a>" + dv.displayValue + "</a>";
        dv.link = val.getLink().getLink();
        return dv;


      default:
        return new DataValue("???");
    }
  }

  private static class DataValue {
    public String displayValue;
    public Path.Any link;

    public DataValue(String displayValue) {
      this.link = null;
      this.displayValue = displayValue;
    }
  }
}
