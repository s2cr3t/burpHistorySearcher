package org.repeaterSearch;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import burp.api.montoya.http.handler.HttpHandler;

import javax.swing.text.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.border.EmptyBorder;

import javax.swing.*;
import java.awt.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class BurpRepeaterSearchExtension implements BurpExtension, ExtensionUnloadingHandler {
    private MontoyaApi api;
    private JPanel mainPanel;
    private JTextField searchField;
    private JTable resultsTable;
    private DefaultTableModel tableModel;
    private JCheckBox searchRequestCheckbox;
    private JCheckBox searchResponseCheckbox;
    private JCheckBox caseSensitiveCheckbox;
    private JCheckBox regexCheckbox;
    private JCheckBox searchProxyHistoryCheckbox;
    private JCheckBox searchRepeaterTabsCheckbox;
    private JLabel statusLabel;
    private List<SearchResultData> searchResults;

    private JTextPane requestTextPane;
    private JTextPane responseTextPane;
    private final RepeaterMessageCapture messageCapture = new RepeaterMessageCapture();
    private String searchKeyword;
    private boolean isCaseSensitive;
    private boolean isRegex;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;

        api.extension().setName("Repeater Search");

        searchResults = new ArrayList<>();

        createUI();

        api.userInterface().registerSuiteTab("Repeater Search", mainPanel);

        api.http().registerHttpHandler(messageCapture);

        api.extension().registerUnloadingHandler(this);

        api.logging().logToOutput("Repeater Search插件已加载");
    }


    private class RepeaterMessageCapture implements HttpHandler {
        private final Map<String, HttpRequestResponse> repeaterRequests = new ConcurrentHashMap<>();
        private final Map<String, String> responseCharsets = new ConcurrentHashMap<>();

        public Map<String, HttpRequestResponse> getRepeaterRequests() {
            return repeaterRequests;
        }

        public Map<String, String> getRequestCharsets() {
            return requestCharsets;
        }

        public Map<String, String> getResponseCharsets() {
            return responseCharsets;
        }
        private String determineCharset(List<HttpHeader> headers) {
            for (HttpHeader header : headers) {
                if (header.name().equalsIgnoreCase("Content-Type")) {
                    String value = header.value();
                    int charsetIndex = value.toLowerCase().indexOf("charset=");
                    if (charsetIndex >= 0) {
                        String charset = value.substring(charsetIndex + 8).trim();
                        if (charset.startsWith("\"") && charset.endsWith("\"")) {
                            charset = charset.substring(1, charset.length() - 1);
                        }
                        int semicolonIndex = charset.indexOf(';');
                        if (semicolonIndex > 0) {
                            charset = charset.substring(0, semicolonIndex);
                        }
                        return charset;
                    }
                }
            }

            if (doesHeaderContain(headers, "Content-Type", "text/html") ||
                    doesHeaderContain(headers, "Content-Type", "application/xml")) {
                return "UTF-8";
            }

            return "UTF-8";
        }
        private boolean doesHeaderContain(List<HttpHeader> headers, String headerName, String containsValue) {
            for (HttpHeader header : headers) {
                if (header.name().equalsIgnoreCase(headerName) &&
                        header.value().toLowerCase().contains(containsValue.toLowerCase())) {
                    return true;
                }
            }
            return false;
        }
        @Override
        public burp.api.montoya.http.handler.RequestToBeSentAction handleHttpRequestToBeSent(burp.api.montoya.http.handler.HttpRequestToBeSent requestToBeSent) {
            try {
                if (requestToBeSent.toolSource().isFromTool(burp.api.montoya.core.ToolType.REPEATER)) {
                    HttpRequest request = null;
                    try {
                        java.lang.reflect.Method getRequestMethod = requestToBeSent.getClass().getMethod("request");
                        request = (HttpRequest) getRequestMethod.invoke(requestToBeSent);
                    } catch (Exception e) {
                        try {
                            java.lang.reflect.Method getRequestMethod = requestToBeSent.getClass().getMethod("getRequest");
                            request = (HttpRequest) getRequestMethod.invoke(requestToBeSent);
                        } catch (Exception ex) {
                            api.logging().logToError("无法获取请求对象: " + ex.getMessage());
                            return burp.api.montoya.http.handler.RequestToBeSentAction.continueWith(requestToBeSent);
                        }
                    }

                    if (request != null) {
                        String charset = determineCharset(request.headers());

                        api.logging().logToOutput("请求使用的字符集: " + charset);

                        String requestId = generateRequestId(request);

                        repeaterRequests.put(requestId, HttpRequestResponse.httpRequestResponse(
                                request,
                                null
                        ));

                        requestCharsets.put(requestId, charset);

                        api.logging().logToOutput("捕获来自Repeater的请求: " + request.url());
                    }
                }
            } catch (Exception e) {
                api.logging().logToError("处理Repeater请求时出错: " + e.getMessage());
            }

            return burp.api.montoya.http.handler.RequestToBeSentAction.continueWith(requestToBeSent);
        }

        @Override
        public burp.api.montoya.http.handler.ResponseReceivedAction handleHttpResponseReceived(burp.api.montoya.http.handler.HttpResponseReceived responseReceived) {
            try {
                if (responseReceived.toolSource().isFromTool(burp.api.montoya.core.ToolType.REPEATER)) {
                    HttpRequest initiatingRequest = null;
                    HttpResponse response = null;

                    try {
                        java.lang.reflect.Method getRequestMethod = responseReceived.getClass().getMethod("initiatingRequest");
                        initiatingRequest = (HttpRequest) getRequestMethod.invoke(responseReceived);
                    } catch (Exception e) {
                        try {
                            java.lang.reflect.Method getRequestMethod = responseReceived.getClass().getMethod("getInitiatingRequest");
                            initiatingRequest = (HttpRequest) getRequestMethod.invoke(responseReceived);
                        } catch (Exception ex) {
                            api.logging().logToError("无法获取原始请求: " + ex.getMessage());
                        }
                    }

                    try {
                        java.lang.reflect.Method getResponseMethod = responseReceived.getClass().getMethod("response");
                        response = (HttpResponse) getResponseMethod.invoke(responseReceived);
                    } catch (Exception e) {
                        try {
                            java.lang.reflect.Method getResponseMethod = responseReceived.getClass().getMethod("getResponse");
                            response = (HttpResponse) getResponseMethod.invoke(responseReceived);
                        } catch (Exception ex) {
                            api.logging().logToError("无法获取响应: " + ex.getMessage());
                        }
                    }

                    if (initiatingRequest != null && response != null) {
                        String requestId = generateRequestId(initiatingRequest);

                        String charset = determineCharset(response.headers());
                        api.logging().logToOutput("响应使用的字符集: " + charset);

                        responseCharsets.put(requestId, charset);

                        if (repeaterRequests.containsKey(requestId)) {
                            repeaterRequests.put(requestId, HttpRequestResponse.httpRequestResponse(
                                    initiatingRequest,
                                    response
                            ));

                            api.logging().logToOutput("捕获来自Repeater的响应: " + initiatingRequest.url());
                        }
                    }
                }
            } catch (Exception e) {
                api.logging().logToError("处理Repeater响应时出错: " + e.getMessage());
            }

            return burp.api.montoya.http.handler.ResponseReceivedAction.continueWith(responseReceived);
        }
    }

    private void createUI() {
        mainPanel = new JPanel(new BorderLayout());

        JPanel authorPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JLabel authorLabel = new JLabel("作者：纸飞机");
        authorLabel.setFont(new Font("Dialog", Font.BOLD, 12));
        authorPanel.add(authorLabel);

        JPanel searchPanel = new JPanel(new BorderLayout());

        JPanel inputPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        searchField = new JTextField(30);
        JButton searchButton = new JButton("搜索");
        searchButton.setBackground(new Color(66, 139, 202));
        searchButton.setForeground(Color.WHITE);
        searchButton.setFocusPainted(false);
        searchButton.addActionListener(e -> performSearch());

        inputPanel.add(new JLabel("搜索关键词: "));
        inputPanel.add(searchField);
        inputPanel.add(searchButton);

        JPanel optionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        searchRequestCheckbox = new JCheckBox("搜索请求", true);
        searchResponseCheckbox = new JCheckBox("搜索响应", true);
        caseSensitiveCheckbox = new JCheckBox("区分大小写", false);
        regexCheckbox = new JCheckBox("使用正则表达式", false);

        searchProxyHistoryCheckbox = new JCheckBox("搜索历史记录", true);
        searchRepeaterTabsCheckbox = new JCheckBox("搜索Repeater标签页", true);

        optionsPanel.add(searchRequestCheckbox);
        optionsPanel.add(searchResponseCheckbox);
        optionsPanel.add(caseSensitiveCheckbox);
        optionsPanel.add(regexCheckbox);
        optionsPanel.add(searchProxyHistoryCheckbox);
        optionsPanel.add(searchRepeaterTabsCheckbox);

        searchPanel.add(inputPanel, BorderLayout.NORTH);
        searchPanel.add(optionsPanel, BorderLayout.CENTER);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(authorPanel, BorderLayout.NORTH);
        topPanel.add(searchPanel, BorderLayout.CENTER);

        mainPanel.add(topPanel, BorderLayout.NORTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setResizeWeight(0.5);

        String[] columnNames = {"序号", "来源", "URL", "时间", "匹配位置", "匹配内容", "操作"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        resultsTable = new JTable(tableModel);
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

        resultsTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        resultsTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        resultsTable.getColumnModel().getColumn(2).setPreferredWidth(150);
        resultsTable.getColumnModel().getColumn(3).setPreferredWidth(120);
        resultsTable.getColumnModel().getColumn(4).setPreferredWidth(80);
        resultsTable.getColumnModel().getColumn(5).setPreferredWidth(280);
        resultsTable.getColumnModel().getColumn(6).setPreferredWidth(100);

        resultsTable.getColumnModel().getColumn(4).setCellRenderer(new TagRenderer());

        resultsTable.getColumnModel().getColumn(6).setCellRenderer(new ButtonRenderer());

        resultsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showRequestResponseDetails();
            }
        });

        JScrollPane tableScrollPane = new JScrollPane(resultsTable);

        JPanel detailsPanel = new JPanel(new BorderLayout());
        JSplitPane detailsSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        requestTextPane = new JTextPane();
        requestTextPane.setEditable(false);
        requestTextPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane requestScrollPane = new JScrollPane(requestTextPane);
        JPanel requestPanel = new JPanel(new BorderLayout());
        JLabel requestLabel = new JLabel(" 请求");
        requestLabel.setIcon(createColorIcon(new Color(65, 131, 196), 12, 12));
        requestLabel.setBorder(new EmptyBorder(5, 5, 5, 0));
        requestPanel.add(requestLabel, BorderLayout.NORTH);
        requestPanel.add(requestScrollPane, BorderLayout.CENTER);

        responseTextPane = new JTextPane();
        responseTextPane.setEditable(false);
        responseTextPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane responseScrollPane = new JScrollPane(responseTextPane);
        JPanel responsePanel = new JPanel(new BorderLayout());
        JLabel responseLabel = new JLabel(" 响应");
        responseLabel.setIcon(createColorIcon(new Color(76, 175, 80), 12, 12));
        responseLabel.setBorder(new EmptyBorder(5, 5, 5, 0));
        responsePanel.add(responseLabel, BorderLayout.NORTH);
        responsePanel.add(responseScrollPane, BorderLayout.CENTER);

        detailsSplitPane.setLeftComponent(requestPanel);
        detailsSplitPane.setRightComponent(responsePanel);
        detailsSplitPane.setResizeWeight(0.5);

        detailsPanel.add(detailsSplitPane, BorderLayout.CENTER);

        splitPane.setTopComponent(tableScrollPane);
        splitPane.setBottomComponent(detailsPanel);

        mainPanel.add(splitPane, BorderLayout.CENTER);

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusLabel = new JLabel("准备就绪");
        statusPanel.add(statusLabel);
        mainPanel.add(statusPanel, BorderLayout.SOUTH);

        this.requestTextArea = requestTextArea;
        this.responseTextArea = responseTextArea;

        final ButtonRenderer buttonRenderer = new ButtonRenderer();
        resultsTable.getColumnModel().getColumn(6).setCellRenderer(buttonRenderer);

        MouseAdapter tableMouseAdapter = new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                buttonRenderer.setHovered(false, -1);
                resultsTable.repaint();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                int row = resultsTable.rowAtPoint(e.getPoint());
                int col = resultsTable.columnAtPoint(e.getPoint());

                if (col == 6 && row >= 0) {
                    buttonRenderer.setHovered(true, row);
                } else {
                    buttonRenderer.setHovered(false, -1);
                }
                resultsTable.repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                int row = resultsTable.rowAtPoint(e.getPoint());
                int col = resultsTable.columnAtPoint(e.getPoint());

                if (col == 6 && row >= 0) {
                    goToRepeaterTab(row);
                }
            }
        };
        resultsTable.addMouseListener(tableMouseAdapter);
        resultsTable.addMouseMotionListener(tableMouseAdapter);
    }
    private Icon createColorIcon(Color color, int width, int height) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2d = (Graphics2D) g.create();
                g2d.setColor(color);
                g2d.fillRect(x, y, width, height);
                g2d.dispose();
            }

            @Override
            public int getIconWidth() {
                return width;
            }

            @Override
            public int getIconHeight() {
                return height;
            }
        };
    }
    private String generateRequestId(HttpRequest request) {
        return request.url() + "|" + request.method() + "|" + System.identityHashCode(request);
    }
    private void performSearch() {
        String keyword = searchField.getText().trim();
        if (keyword.isEmpty()) {
            api.logging().logToError("搜索关键词不能为空");
            JOptionPane.showMessageDialog(mainPanel, "请输入搜索关键词", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        StringBuilder hexString = new StringBuilder();
        for (byte b : keyword.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            hexString.append(String.format("%02x ", b & 0xff));
        }
        api.logging().logToOutput("搜索关键词: '" + keyword + "', 十六进制: " + hexString.toString());

        boolean searchRequest = searchRequestCheckbox.isSelected();
        boolean searchResponse = searchResponseCheckbox.isSelected();
        boolean caseSensitive = caseSensitiveCheckbox.isSelected();
        boolean useRegex = regexCheckbox.isSelected();
        boolean searchProxyHistory = searchProxyHistoryCheckbox.isSelected();
        boolean searchRepeaterTabs = searchRepeaterTabsCheckbox.isSelected();

        if (!searchRequest && !searchResponse) {
            api.logging().logToError("请至少选择搜索请求或响应");
            JOptionPane.showMessageDialog(mainPanel, "请至少选择搜索请求或响应", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (!searchProxyHistory && !searchRepeaterTabs) {
            api.logging().logToError("请至少选择一个搜索数据源");
            JOptionPane.showMessageDialog(mainPanel, "请至少选择搜索历史记录或Repeater标签页", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        statusLabel.setText("正在搜索...");

        searchResults.clear();
        tableModel.setRowCount(0);

        this.searchKeyword = keyword;
        this.isCaseSensitive = caseSensitive;
        this.isRegex = useRegex;

        SwingWorker<List<SearchResultData>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<SearchResultData> doInBackground() {
                List<SearchResultData> results = new ArrayList<>();

                if (searchProxyHistory) {
                    results.addAll(searchProxyHistory(keyword, searchRequest, searchResponse, caseSensitive, useRegex));
                }

                if (searchRepeaterTabs) {
                    results.addAll(searchRepeaterContent(keyword, searchRequest, searchResponse, caseSensitive, useRegex));
                }

                return results;
            }

            @Override
            protected void done() {
                try {
                    searchResults = get();

                    tableModel.setRowCount(0);

                    int index = 1;
                    for (SearchResultData result : searchResults) {
                        tableModel.addRow(new Object[]{
                                String.valueOf(index++),
                                result.source,
                                result.url,
                                result.timestamp,
                                result.matchLocation,
                                result.matchedContent,
                                ""
                        });
                    }

                    if (searchResults.isEmpty()) {
                        api.logging().logToOutput("没有找到匹配的内容");
                        JOptionPane.showMessageDialog(mainPanel, "没有找到匹配的内容", "搜索结果", JOptionPane.INFORMATION_MESSAGE);
                        statusLabel.setText("搜索完成 - 未找到结果");
                    } else {
                        api.logging().logToOutput("找到 " + searchResults.size() + " 个匹配结果");
                        statusLabel.setText("搜索完成 - 找到 " + searchResults.size() + " 个结果");
                    }
                } catch (Exception e) {
                    api.logging().logToError("处理搜索结果时出错: " + e.getMessage());
                    e.printStackTrace();
                    statusLabel.setText("搜索出错");
                }
            }
        };

        worker.execute();
    }

    private List<SearchResultData> searchProxyHistory(String keyword, boolean searchRequest, boolean searchResponse,
                                                      boolean caseSensitive, boolean useRegex) {
        List<SearchResultData> results = new ArrayList<>();

        try {
            List<ProxyHttpRequestResponse> proxyHistory = api.proxy().history();

            for (ProxyHttpRequestResponse proxyReqRes : proxyHistory) {
                try {
                    if (proxyReqRes == null) continue;

                    HttpRequest request = proxyReqRes.request();
                    String url = request.url();

                    String timestamp = "";
                    try {
                        java.lang.reflect.Method getTimeMethod = proxyReqRes.getClass().getMethod("timeStamp");
                        Long time = (Long) getTimeMethod.invoke(proxyReqRes);
                        timestamp = formatTimestamp(time);
                    } catch (Exception e) {
                        try {
                            java.lang.reflect.Method getTimeMethod = proxyReqRes.getClass().getMethod("getTimeStamp");
                            Long time = (Long) getTimeMethod.invoke(proxyReqRes);
                            timestamp = formatTimestamp(time);
                        } catch (Exception ex) {
                            timestamp = extractTimestampFromHeaders(request);
                        }
                    }

                    if (searchRequest) {
                        String requestCharset = messageCapture.determineCharset(request.headers());

                        byte[] requestBytes = request.toByteArray().getBytes();
                        String requestString;

                        try {
                            requestString = new String(requestBytes, requestCharset);
                        } catch (Exception e) {
                            requestString = request.toString();
                        }

                        List<String> matches = findMatches(requestString, keyword, caseSensitive, useRegex);

                        if (!matches.isEmpty()) {
                            for (String match : matches) {
                                results.add(new SearchResultData("历史记录", url, timestamp, "请求", match, proxyReqRes, null));
                            }
                        }
                    }

                    if (searchResponse && proxyReqRes.response() != null) {
                        String responseCharset = messageCapture.determineCharset(proxyReqRes.response().headers());

                        byte[] responseBytes = proxyReqRes.response().toByteArray().getBytes();
                        String responseString;

                        try {
                            responseString = new String(responseBytes, responseCharset);
                        } catch (Exception e) {
                            responseString = proxyReqRes.response().toString();
                        }

                        List<String> matches = findMatches(responseString, keyword, caseSensitive, useRegex);

                        if (!matches.isEmpty()) {
                            for (String match : matches) {
                                results.add(new SearchResultData("历史记录", url, timestamp, "响应", match, proxyReqRes, null));
                            }
                        }
                    }
                } catch (Exception e) {
                    api.logging().logToError("处理历史记录请求时出错: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            api.logging().logToError("搜索历史记录时出错: " + e.getMessage());
            e.printStackTrace();
        }

        return results;
    }


    private List<SearchResultData> searchRepeaterContent(String keyword, boolean searchRequest, boolean searchResponse,
                                                         boolean caseSensitive, boolean useRegex) {
        List<SearchResultData> results = new ArrayList<>();

        try {
            Map<String, HttpRequestResponse> repeaterRequests = messageCapture.getRepeaterRequests();
            Map<String, String> requestCharsets = messageCapture.getRequestCharsets();
            Map<String, String> responseCharsets = messageCapture.getResponseCharsets();

            api.logging().logToOutput("开始搜索Repeater内容，当前缓存中有 " + repeaterRequests.size() + " 个请求/响应");

            for (Map.Entry<String, HttpRequestResponse> entry : repeaterRequests.entrySet()) {
                try {
                    String requestId = entry.getKey();
                    HttpRequestResponse reqRes = entry.getValue();
                    if (reqRes == null) continue;

                    HttpRequest request = reqRes.request();
                    if (request == null) continue;

                    String url = request.url();
                    String timestamp = formatTimestamp(System.currentTimeMillis());

                    String requestCharset = requestCharsets.getOrDefault(requestId, "UTF-8");
                    String responseCharset = responseCharsets.getOrDefault(requestId, "UTF-8");

                    if (searchRequest) {
                        byte[] requestBytes = request.toByteArray().getBytes();
                        String requestString;

                        try {
                            requestString = new String(requestBytes, requestCharset);
                        } catch (Exception e) {
                            requestString = request.toString();
                        }

                        List<String> matches = findMatches(requestString, keyword, caseSensitive, useRegex);

                        if (!matches.isEmpty()) {
                            for (String match : matches) {
                                results.add(new SearchResultData("Repeater", url, timestamp, "请求", match, null, reqRes));
                            }
                        }
                    }

                    if (searchResponse && reqRes.response() != null) {
                        byte[] responseBytes = reqRes.response().toByteArray().getBytes();
                        String responseString;

                        try {
                            responseString = new String(responseBytes, responseCharset);
                        } catch (Exception e) {
                            responseString = reqRes.response().toString();
                        }

                        List<String> matches = findMatches(responseString, keyword, caseSensitive, useRegex);

                        if (!matches.isEmpty()) {
                            for (String match : matches) {
                                results.add(new SearchResultData("Repeater", url, timestamp, "响应", match, null, reqRes));
                            }
                        }
                    }
                } catch (Exception e) {
                    api.logging().logToError("处理Repeater请求时出错: " + e.getMessage());
                }
            }

            api.logging().logToOutput("Repeater搜索完成，找到 " + results.size() + " 个匹配项");
        } catch (Exception e) {
            api.logging().logToError("搜索Repeater内容时出错: " + e.getMessage());
            e.printStackTrace();
        }

        return results;
    }
    private String extractTimestampFromHeaders(HttpRequest request) {
        try {
            String dateHeader = request.headerValue("Date");
            if (dateHeader != null && !dateHeader.isEmpty()) {
                return dateHeader;
            }

            return formatTimestamp(System.currentTimeMillis());
        } catch (Exception e) {
            return formatTimestamp(System.currentTimeMillis());
        }
    }
    private String formatTimestamp(long timestamp) {
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return sdf.format(new java.util.Date(timestamp));
        } catch (Exception e) {
            return "未知时间";
        }
    }
    private List<String> findMatches(String text, String keyword, boolean caseSensitive, boolean useRegex) {
        List<String> matches = new ArrayList<>();

        try {
            if (text == null) text = "";

            api.logging().logToOutput("搜索文本长度: " + text.length() + ", 关键词: '" + keyword + "'");

            if (useRegex) {
                int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
                flags |= Pattern.UNICODE_CASE;
                flags |= Pattern.UNICODE_CHARACTER_CLASS;

                try {
                    Pattern pattern = Pattern.compile(keyword, flags);
                    Matcher matcher = pattern.matcher(text);

                    while (matcher.find()) {
                        int start = Math.max(0, matcher.start() - 20);
                        int end = Math.min(text.length(), matcher.end() + 20);
                        String context = text.substring(start, end);

                        if (start > 0) context = "..." + context;
                        if (end < text.length()) context = context + "...";

                        api.logging().logToOutput("找到正则匹配: '" + matcher.group() + "'");
                        matches.add(context);
                    }
                } catch (Exception e) {
                    api.logging().logToError("正则表达式错误: " + e.getMessage());
                    useRegex = false;
                }
            }

            if (!useRegex) {
                if (!caseSensitive) {
                    String normalizedText = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).toLowerCase();
                    String normalizedKeyword = java.text.Normalizer.normalize(keyword, java.text.Normalizer.Form.NFD).toLowerCase();

                    int index = 0;
                    while (index < normalizedText.length()) {
                        index = normalizedText.indexOf(normalizedKeyword, index);
                        if (index == -1) break;

                        int start = Math.max(0, index - 20);
                        int end = Math.min(text.length(), index + normalizedKeyword.length() + 20);
                        String context = text.substring(start, end);

                        if (start > 0) context = "..." + context;
                        if (end < text.length()) context = context + "...";

                        api.logging().logToOutput("找到普通匹配: 位置 " + index);
                        matches.add(context);

                        index += normalizedKeyword.length();
                    }
                } else {
                    int index = 0;
                    while (index < text.length()) {
                        index = text.indexOf(keyword, index);
                        if (index == -1) break;

                        int start = Math.max(0, index - 20);
                        int end = Math.min(text.length(), index + keyword.length() + 20);
                        String context = text.substring(start, end);

                        if (start > 0) context = "..." + context;
                        if (end < text.length()) context = context + "...";

                        api.logging().logToOutput("找到区分大小写匹配: 位置 " + index);
                        matches.add(context);

                        index += keyword.length();
                    }
                }
            }

            api.logging().logToOutput("找到匹配数: " + matches.size());
        } catch (Exception e) {
            api.logging().logToError("搜索匹配时出错: " + e.getMessage());
            e.printStackTrace();
        }

        return matches;
    }
    /**
     * Find a valid string boundary that doesn't break Unicode characters
     * @param text The text to search in
     * @param position The initial position
     * @param forward True to search forward, false to search backward
     * @return A valid string position that doesn't break Unicode characters
     */
    private int findValidStringBoundary(String text, int position, boolean forward) {
        if (position <= 0) return 0;
        if (position >= text.length()) return text.length();

        if (forward) {
            if (position < text.length() - 1 && Character.isLowSurrogate(text.charAt(position))) {
                return position + 1;
            }
        } else {
            if (position > 0 && Character.isHighSurrogate(text.charAt(position - 1))) {
                return position - 1;
            }
        }

        return position;
    }

    private void goToRepeaterTab(int rowIndex) {
        try {
            if (rowIndex < 0 || rowIndex >= searchResults.size()) {
                api.logging().logToError("无效的行索引: " + rowIndex);
                return;
            }

            SearchResultData data = searchResults.get(rowIndex);

            if (data != null) {
                if (data.repeaterRequestResponse != null) {
                    String tabName = "搜索结果 - " + data.url;
                    HttpRequest request = data.repeaterRequestResponse.request();

                    api.repeater().sendToRepeater(request, tabName);
                    api.logging().logToOutput("已发送到Repeater标签页: " + tabName);

                    JOptionPane.showMessageDialog(mainPanel,
                            "已将请求发送到Repeater中的新标签页:\n" + tabName,
                            "发送成功",
                            JOptionPane.INFORMATION_MESSAGE);
                }
                else if (data.proxyRequestResponse != null) {
                    HttpRequest request = data.proxyRequestResponse.request();
                    String tabName = "从搜索 - " + data.url;

                    api.repeater().sendToRepeater(request, tabName);
                    api.logging().logToOutput("成功将请求发送到Repeater" );

                    JOptionPane.showMessageDialog(mainPanel,
                            "已将请求发送到Repeater中的新标签页:\n" ,
                            "发送成功",
                            JOptionPane.INFORMATION_MESSAGE);
                }
                else {
                    api.logging().logToError("无法获取选中行的请求数据");
                    JOptionPane.showMessageDialog(mainPanel,
                            "无法获取请求数据",
                            "错误",
                            JOptionPane.ERROR_MESSAGE);
                }
            } else {
                api.logging().logToError("无法获取选中行的数据");
                JOptionPane.showMessageDialog(mainPanel,
                        "无法获取数据",
                        "错误",
                        JOptionPane.ERROR_MESSAGE);
            }
        } catch (Exception e) {
            api.logging().logToError("跳转或发送到Repeater时出错: " + e.getMessage());
            JOptionPane.showMessageDialog(mainPanel,
                    "操作出错: " + e.getMessage(),
                    "错误",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    @Override
    public void extensionUnloaded() {
        api.logging().logToOutput("Repeater Search插件已卸载");
    }

    private static class SearchResultData {
        String source;
        String url;
        String timestamp;
        String matchLocation;
        String matchedContent;
        ProxyHttpRequestResponse proxyRequestResponse;
        HttpRequestResponse repeaterRequestResponse;

        public SearchResultData(String source, String url, String timestamp, String matchLocation, String matchedContent,
                                ProxyHttpRequestResponse proxyRequestResponse, HttpRequestResponse repeaterRequestResponse) {
            this.source = source;
            this.url = url;
            this.timestamp = timestamp;
            this.matchLocation = matchLocation;
            this.matchedContent = matchedContent;
            this.proxyRequestResponse = proxyRequestResponse;
            this.repeaterRequestResponse = repeaterRequestResponse;
        }
    }

    public static void main(String[] args) {
        System.out.println("Burp Repeater Search Extension");

        JFrame frame = new JFrame("Repeater Search Test");
        frame.setSize(800, 600);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        BurpRepeaterSearchExtension extension = new BurpRepeaterSearchExtension();
        extension.createUI();

        frame.add(extension.mainPanel);
        frame.setVisible(true);
    }
    private class ButtonRenderer extends JButton implements TableCellRenderer {
        private final Color defaultBackground = new Color(66, 139, 202);
        private final Color hoverBackground = new Color(51, 122, 183);
        private boolean isHovered = false;
        private int hoveredRow = -1;

        public ButtonRenderer() {
            setOpaque(true);
            setBorderPainted(true);
            setBorder(new EmptyBorder(4, 8, 4, 8));
            setForeground(Color.WHITE);
            setFocusPainted(false);
        }

        public void setHovered(boolean hovered, int row) {
            isHovered = hovered;
            hoveredRow = row;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            setText("发送到repeater");
            setBackground(row == hoveredRow && isHovered ? hoverBackground : defaultBackground);
            return this;
        }
    }

    private JTextArea requestTextArea;
    private JTextArea responseTextArea;

    private List<Position> highlightPositions = new ArrayList<>();

    private static class Position {
        int offset;
        int length;

        Position(int offset, int length) {
            this.offset = offset;
            this.length = length;
        }
    }
    private class HttpSyntaxHighlighter {
        private final StyleContext styleContext = StyleContext.getDefaultStyleContext();
        private final AttributeSet headerStyle = styleContext.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, new Color(0, 128, 0));
        private final AttributeSet methodStyle = styleContext.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, new Color(204, 102, 0));
        private final AttributeSet urlStyle = styleContext.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, new Color(0, 0, 204));
        private final AttributeSet paramStyle = styleContext.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, new Color(153, 0, 153));
        private final AttributeSet valueStyle = styleContext.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, new Color(0, 153, 153));
        private final AttributeSet defaultStyle = styleContext.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, Color.BLACK);

        private String searchKeyword;
        private boolean isCaseSensitive;
        private boolean isRegex;

        private final List<Position> highlightPositions = new ArrayList<>();

        private static class Position {
            int offset;
            int length;

            Position(int offset, int length) {
                this.offset = offset;
                this.length = length;
            }
        }

        public void setSearchKeyword(String keyword, boolean caseSensitive, boolean regex) {
            this.searchKeyword = keyword;
            this.isCaseSensitive = caseSensitive;
            this.isRegex = regex;
        }

        public void highlightHttpContent(JTextPane textPane, String content, boolean isRequest) {
            Document doc = textPane.getDocument();
            try {
                doc.remove(0, doc.getLength());

                String[] lines = content.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];

                    if (i == 0 && isRequest) {
                        String[] parts = line.split(" ", 3);
                        if (parts.length >= 3) {
                            doc.insertString(doc.getLength(), parts[0] + " ", methodStyle);

                            String url = parts[1];
                            int queryIndex = url.indexOf('?');
                            if (queryIndex > 0) {
                                doc.insertString(doc.getLength(), url.substring(0, queryIndex), urlStyle);
                                highlightQueryParams(doc, url.substring(queryIndex));
                            } else {
                                doc.insertString(doc.getLength(), url, urlStyle);
                            }

                            doc.insertString(doc.getLength(), " " + parts[2] + "\n", defaultStyle);
                        } else {
                            insertHighlightedLine(doc, line + "\n");
                        }
                    }
                    else if (i == 0 && !isRequest) {
                        String[] parts = line.split(" ", 3);
                        if (parts.length >= 3) {
                            doc.insertString(doc.getLength(), parts[0] + " ", defaultStyle);
                            doc.insertString(doc.getLength(), parts[1] + " ", methodStyle);
                            insertHighlightedText(doc, parts[2] + "\n");
                        } else {
                            insertHighlightedLine(doc, line + "\n");
                        }
                    }
                    else if (line.contains(":") && !line.trim().isEmpty() && line.indexOf(":") < line.length() - 1) {
                        int colonPos = line.indexOf(":");
                        doc.insertString(doc.getLength(), line.substring(0, colonPos + 1), headerStyle);
                        insertHighlightedText(doc, line.substring(colonPos + 1) + "\n");
                    }
                    else {
                        insertHighlightedLine(doc, line + "\n");
                    }
                }
            } catch (BadLocationException e) {
                try {
                    doc.remove(0, doc.getLength());
                    insertHighlightedLine(doc, content);
                } catch (BadLocationException ex) {
                }
            }
        }

        private void insertHighlightedText(Document doc, String text) throws BadLocationException {
            if (searchKeyword == null || searchKeyword.isEmpty()) {
                doc.insertString(doc.getLength(), text, defaultStyle);
                return;
            }

            if (doc.getLength() == 0) {
                highlightPositions.clear();
            }

            if (isRegex) {
                int flags = isCaseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
                flags |= Pattern.UNICODE_CASE;
                flags |= Pattern.UNICODE_CHARACTER_CLASS;

                try {
                    Pattern pattern = Pattern.compile(searchKeyword, flags);
                    Matcher matcher = pattern.matcher(text);

                    int lastEnd = 0;
                    while (matcher.find()) {
                        doc.insertString(doc.getLength(), text.substring(lastEnd, matcher.start()), defaultStyle);

                        int highlightStart = doc.getLength();

                        doc.insertString(doc.getLength(), text.substring(matcher.start(), matcher.end()),
                                getHighlightedStyle(defaultStyle));

                        highlightPositions.add(new Position(highlightStart, matcher.end() - matcher.start()));

                        lastEnd = matcher.end();
                    }

                    if (lastEnd < text.length()) {
                        doc.insertString(doc.getLength(), text.substring(lastEnd), defaultStyle);
                    }
                } catch (Exception e) {
                    doc.insertString(doc.getLength(), text, defaultStyle);
                }
            } else {
                if (!isCaseSensitive) {
                    String normalizedText = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).toLowerCase();
                    String normalizedKeyword = java.text.Normalizer.normalize(searchKeyword, java.text.Normalizer.Form.NFD).toLowerCase();

                    int lastIndex = 0;
                    int searchIndex = 0;

                    while ((searchIndex = normalizedText.indexOf(normalizedKeyword, searchIndex)) != -1) {
                        doc.insertString(doc.getLength(), text.substring(lastIndex, searchIndex), defaultStyle);

                        int highlightStart = doc.getLength();

                        doc.insertString(doc.getLength(), text.substring(searchIndex, searchIndex + searchKeyword.length()),
                                getHighlightedStyle(defaultStyle));

                        highlightPositions.add(new Position(highlightStart, searchKeyword.length()));

                        searchIndex += normalizedKeyword.length();
                        lastIndex = searchIndex;
                    }

                    if (lastIndex < text.length()) {
                        doc.insertString(doc.getLength(), text.substring(lastIndex), defaultStyle);
                    }
                } else {
                    int lastIndex = 0;
                    int searchIndex = 0;

                    while ((searchIndex = text.indexOf(searchKeyword, searchIndex)) != -1) {
                        doc.insertString(doc.getLength(), text.substring(lastIndex, searchIndex), defaultStyle);

                        int highlightStart = doc.getLength();

                        doc.insertString(doc.getLength(), text.substring(searchIndex, searchIndex + searchKeyword.length()),
                                getHighlightedStyle(defaultStyle));

                        highlightPositions.add(new Position(highlightStart, searchKeyword.length()));

                        searchIndex += searchKeyword.length();
                        lastIndex = searchIndex;
                    }

                    if (lastIndex < text.length()) {
                        doc.insertString(doc.getLength(), text.substring(lastIndex), defaultStyle);
                    }
                }
            }
        }


        private void insertHighlightedLine(Document doc, String line) throws BadLocationException {
            insertHighlightedText(doc, line);
        }

        private AttributeSet getHighlightedStyle(AttributeSet baseStyle) {
            SimpleAttributeSet highlightStyle = new SimpleAttributeSet(baseStyle);
            StyleConstants.setBackground(highlightStyle, new Color(255, 255, 0, 128));
            StyleConstants.setForeground(highlightStyle, Color.BLACK);
            return highlightStyle;
        }

        private void highlightQueryParams(Document doc, String queryPart) throws BadLocationException {
            String[] pairs = queryPart.substring(1).split("&");
            doc.insertString(doc.getLength(), "?", defaultStyle);

            for (int i = 0; i < pairs.length; i++) {
                String pair = pairs[i];
                int eqPos = pair.indexOf('=');

                if (eqPos > 0) {
                    insertHighlightedParamText(doc, pair.substring(0, eqPos), paramStyle);
                    doc.insertString(doc.getLength(), "=", defaultStyle);
                    insertHighlightedParamText(doc, pair.substring(eqPos + 1), valueStyle);
                } else {
                    insertHighlightedParamText(doc, pair, paramStyle);
                }

                if (i < pairs.length - 1) {
                    doc.insertString(doc.getLength(), "&", defaultStyle);
                }
            }
        }

        private void insertHighlightedParamText(Document doc, String text, AttributeSet baseStyle) throws BadLocationException {
            if (searchKeyword == null || searchKeyword.isEmpty()) {
                doc.insertString(doc.getLength(), text, baseStyle);
                return;
            }

            if (isRegex) {
                int flags = isCaseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
                flags |= Pattern.UNICODE_CHARACTER_CLASS;
                Pattern pattern = Pattern.compile(searchKeyword, flags);
                Matcher matcher = pattern.matcher(text);

                int lastEnd = 0;
                while (matcher.find()) {
                    doc.insertString(doc.getLength(), text.substring(lastEnd, matcher.start()), baseStyle);

                    int highlightStart = doc.getLength();

                    doc.insertString(doc.getLength(), text.substring(matcher.start(), matcher.end()),
                            getHighlightedStyle(baseStyle));

                    highlightPositions.add(new Position(highlightStart, matcher.end() - matcher.start()));

                    lastEnd = matcher.end();
                }
                if (lastEnd < text.length()) {
                    doc.insertString(doc.getLength(), text.substring(lastEnd), baseStyle);
                }
            } else {
                String searchFor = isCaseSensitive ? searchKeyword : searchKeyword.toLowerCase();
                String textToSearch = isCaseSensitive ? text : text.toLowerCase();

                int searchIndex = 0;
                int lastIndex = 0;

                while ((searchIndex = textToSearch.indexOf(searchFor, searchIndex)) != -1) {
                    doc.insertString(doc.getLength(), text.substring(lastIndex, searchIndex), baseStyle);

                    int highlightStart = doc.getLength();

                    doc.insertString(doc.getLength(), text.substring(searchIndex, searchIndex + searchFor.length()),
                            getHighlightedStyle(baseStyle));

                    highlightPositions.add(new Position(highlightStart, searchFor.length()));

                    searchIndex += searchFor.length();
                    lastIndex = searchIndex;
                }

                if (lastIndex < text.length()) {
                    doc.insertString(doc.getLength(), text.substring(lastIndex), baseStyle);
                }
            }
        }

        public void scrollToFirstHighlight(JTextPane textPane) {
            if (!highlightPositions.isEmpty()) {
                try {
                    Position firstHighlight = highlightPositions.get(0);

                    System.out.println("Scrolling to highlight: offset=" + firstHighlight.offset
                            + ", length=" + firstHighlight.length
                            + ", highlights total=" + highlightPositions.size());

                    textPane.setCaretPosition(firstHighlight.offset);

                    textPane.select(firstHighlight.offset, firstHighlight.offset + firstHighlight.length);

                    Rectangle rect = textPane.modelToView2D(firstHighlight.offset).getBounds();
                    if (rect != null) {
                        rect.height = Math.max(rect.height, 20);
                        textPane.scrollRectToVisible(rect);
                    }
                } catch (Exception e) {
                    System.out.println("Error while scrolling: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                System.out.println("No highlights found to scroll to");
            }
        }
    }




    private final Map<String, String> requestCharsets = new ConcurrentHashMap<>();

    private void showRequestResponseDetails() {
        int selectedRow = resultsTable.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < searchResults.size()) {
            SearchResultData data = searchResults.get(selectedRow);
            HttpSyntaxHighlighter requestHighlighter = new HttpSyntaxHighlighter();
            HttpSyntaxHighlighter responseHighlighter = new HttpSyntaxHighlighter();

            String keyword = searchField.getText().trim();
            boolean caseSensitive = caseSensitiveCheckbox.isSelected();
            boolean useRegex = regexCheckbox.isSelected();
            requestHighlighter.setSearchKeyword(keyword, caseSensitive, useRegex);
            responseHighlighter.setSearchKeyword(keyword, caseSensitive, useRegex);

            if (data.proxyRequestResponse != null) {
                HttpRequest request = data.proxyRequestResponse.request();
                if (request != null) {
                    String requestCharset = messageCapture.determineCharset(request.headers());

                    try {
                        byte[] requestBytes = request.toByteArray().getBytes();
                        String requestString = new String(requestBytes, requestCharset);
                        requestHighlighter.highlightHttpContent(requestTextPane, requestString, true);
                    } catch (Exception e) {
                        requestHighlighter.highlightHttpContent(requestTextPane, request.toString(), true);
                    }

                    if ("请求".equals(data.matchLocation)) {
                        requestHighlighter.scrollToFirstHighlight(requestTextPane);
                    }
                } else {
                    requestTextPane.setText("");
                }

                HttpResponse response = data.proxyRequestResponse.response();
                if (response != null) {
                    String responseCharset = messageCapture.determineCharset(response.headers());

                    try {
                        byte[] responseBytes = response.toByteArray().getBytes();
                        String responseString = new String(responseBytes, responseCharset);
                        responseHighlighter.highlightHttpContent(responseTextPane, responseString, false);
                    } catch (Exception e) {
                        responseHighlighter.highlightHttpContent(responseTextPane, response.toString(), false);
                    }

                    if ("响应".equals(data.matchLocation)) {
                        responseHighlighter.scrollToFirstHighlight(responseTextPane);
                    }
                } else {
                    responseTextPane.setText("");
                }
            } else if (data.repeaterRequestResponse != null) {
            } else {
                requestTextPane.setText("无法加载请求数据");
                responseTextPane.setText("无法加载响应数据");
            }
        } else {
            requestTextPane.setText("");
            responseTextPane.setText("");
        }
    }
    private class TagRenderer extends JLabel implements TableCellRenderer {
        private final Color requestColor = new Color(65, 131, 196);
        private final Color responseColor = new Color(76, 175, 80);

        public TagRenderer() {
            setOpaque(true);
            setBorder(new EmptyBorder(2, 4, 2, 4));
            setHorizontalAlignment(CENTER);
            setFont(new Font(getFont().getName(), Font.BOLD, getFont().getSize()));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            if (value != null) {
                String text = value.toString();
                setText(text);

                if ("请求".equals(text)) {
                    setBackground(requestColor);
                    setForeground(Color.WHITE);
                } else if ("响应".equals(text)) {
                    setBackground(responseColor);
                    setForeground(Color.WHITE);
                } else {
                    setBackground(table.getBackground());
                    setForeground(table.getForeground());
                }
            }
            return this;
        }
    }

}
