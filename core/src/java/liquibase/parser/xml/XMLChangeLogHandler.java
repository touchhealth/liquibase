
package liquibase.parser.xml;


import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.SortedSet;
import java.util.Stack;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;

import liquibase.ChangeSet;
import liquibase.DatabaseChangeLog;
import liquibase.FileOpener;
import liquibase.change.AddColumnChange;
import liquibase.change.Change;
import liquibase.change.ChangeFactory;
import liquibase.change.ChangeWithColumns;
import liquibase.change.ColumnConfig;
import liquibase.change.ConstraintsConfig;
import liquibase.change.CreateProcedureChange;
import liquibase.change.CreateTableChange;
import liquibase.change.CreateViewChange;
import liquibase.change.DeleteDataChange;
import liquibase.change.ExecuteShellCommandChange;
import liquibase.change.InsertDataChange;
import liquibase.change.LoadDataChange;
import liquibase.change.LoadDataColumnConfig;
import liquibase.change.ModifyColumnChange;
import liquibase.change.RawSQLChange;
import liquibase.change.StopChange;
import liquibase.change.UpdateDataChange;
import liquibase.change.custom.CustomChangeWrapper;
import liquibase.database.sql.visitor.SqlVisitor;
import liquibase.database.sql.visitor.SqlVisitorFactory;
import liquibase.exception.CustomChangeException;
import liquibase.exception.LiquibaseException;
import liquibase.exception.MigrationFailedException;
import liquibase.log.LogFactory;
import liquibase.parser.ChangeLogParser;
import liquibase.parser.ExpressionExpander;
import liquibase.preconditions.AndPrecondition;
import liquibase.preconditions.CustomPreconditionWrapper;
import liquibase.preconditions.Precondition;
import liquibase.preconditions.PreconditionFactory;
import liquibase.preconditions.PreconditionLogic;
import liquibase.preconditions.Preconditions;
import liquibase.preconditions.SqlPrecondition;
import liquibase.util.ObjectUtil;
import liquibase.util.StringUtils;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;


class XMLChangeLogHandler extends DefaultHandler {

    private static final char LIQUIBASE_FILE_SEPARATOR = '/';

    protected Logger log;

    private DatabaseChangeLog databaseChangeLog;
    private Change change;
    private StringBuffer text;
    private Preconditions rootPrecondition;
    private Stack<PreconditionLogic> preconditionLogicStack = new Stack<PreconditionLogic>();
    private ChangeSet changeSet;
    private String paramName;
    private FileOpener fileOpener;
    private Precondition currentPrecondition;

    private Map<String, Object> changeLogParameters = new HashMap<String, Object>();
    private boolean inRollback = false;

    private boolean inModifySql = false;
    private Collection modifySqlDbmsList;

    protected XMLChangeLogHandler(String physicalChangeLogLocation, FileOpener fileOpener,
            Map<String, Object> properties) {
        this.log = LogFactory.getLogger();
        this.fileOpener = fileOpener;

        this.databaseChangeLog = new DatabaseChangeLog(physicalChangeLogLocation);
        this.databaseChangeLog.setPhysicalFilePath(physicalChangeLogLocation);

        for (Map.Entry entry : System.getProperties().entrySet()) {
            this.changeLogParameters.put(entry.getKey().toString(), entry.getValue());
        }

        for (Map.Entry entry : properties.entrySet()) {
            this.changeLogParameters.put(entry.getKey().toString(), entry.getValue());
        }
    }

    public DatabaseChangeLog getDatabaseChangeLog() {
        return this.databaseChangeLog;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes baseAttributes) throws SAXException {
        Attributes atts = new ExpandingAttributes(baseAttributes);
        try {
            if ("comment".equals(qName)) {
                this.text = new StringBuffer();
            } else if ("validCheckSum".equals(qName)) {
                this.text = new StringBuffer();
            } else if ("databaseChangeLog".equals(qName)) {
                String version = uri.substring(uri.lastIndexOf("/") + 1);
                if (!version.equals(XMLChangeLogParser.getSchemaVersion())) {
                    this.log.warning(this.databaseChangeLog.getPhysicalFilePath() + " is using schema version "
                            + version + " rather than version " + XMLChangeLogParser.getSchemaVersion());
                }
                this.databaseChangeLog.setLogicalFilePath(atts.getValue("logicalFilePath"));
            } else if ("include".equals(qName)) {
                String fileName = atts.getValue("file");
                boolean isRelativeToChangelogFile = Boolean.parseBoolean(atts.getValue("relativeToChangelogFile"));
                this.handleIncludedChangeLog(fileName, isRelativeToChangelogFile,
                        this.databaseChangeLog.getPhysicalFilePath());
            } else if ("includeAll".equals(qName)) {
                String pathName = atts.getValue("path");
                if (!(pathName.endsWith("/") || pathName.endsWith("\\"))) {
                    pathName = pathName + "/";
                }
                this.log.finest("includeAll for " + pathName);
                this.log.finest("Using file opener for includeAll: " + this.fileOpener.getClass().getName());
                Enumeration<URL> resources = this.fileOpener.getResources(pathName);

                boolean foundResource = false;

                while (resources.hasMoreElements()) {
                    URL fileUrl = resources.nextElement();
                    if (fileUrl.toExternalForm().startsWith("jar:")) {
                        foundResource = this.handleJar(fileUrl);
                        continue;
                    }

                    else if (!fileUrl.toExternalForm().startsWith("file:")) {
                        this.log.finest(fileUrl.toExternalForm() + " is not a file path");
                        continue;
                    }
                    File file = new File(fileUrl.toURI());
                    this.log.finest("includeAll using path " + file.getCanonicalPath());
                    if (!file.exists()) {
                        throw new SAXException("includeAll path " + pathName + " could not be found.  Tried in "
                                + file.toString());
                    }
                    if (file.isDirectory()) {
                        this.log.finest(file.getCanonicalPath() + " is a directory");

                        String[] fileNames = file.list();
                        if (fileNames == null) {
                            continue;
                        }
                        SortedSet<String> orderedFiles = new TreeSet<String>();
                        orderedFiles.addAll(Arrays.asList(fileNames));
                        foundResource = this.incluiDiretorio(pathName, orderedFiles);
                    } else {
                        if (this.handleIncludedChangeLog(pathName + file.getName(), false,
                                this.databaseChangeLog.getPhysicalFilePath())) {
                            foundResource = true;
                        }
                    }
                }

                if (!foundResource) {
                    throw new SAXException("Could not find directory " + pathName);
                }
            } else if ((this.changeSet == null) && "changeSet".equals(qName)) {
                boolean alwaysRun = false;
                boolean runOnChange = false;
                if ("true".equalsIgnoreCase(atts.getValue("runAlways"))) {
                    alwaysRun = true;
                }
                if ("true".equalsIgnoreCase(atts.getValue("runOnChange"))) {
                    runOnChange = true;
                }
                this.changeSet =
                        new ChangeSet(atts.getValue("id"), atts.getValue("author"), alwaysRun, runOnChange,
                                this.databaseChangeLog.getFilePath(), this.databaseChangeLog.getPhysicalFilePath(),
                                atts.getValue("context"), atts.getValue("dbms"), Boolean.valueOf(atts
                                        .getValue("runInTransaction")));
                if (StringUtils.trimToNull(atts.getValue("failOnError")) != null) {
                    this.changeSet.setFailOnError(Boolean.parseBoolean(atts.getValue("failOnError")));
                }
            } else if ((this.changeSet != null) && "rollback".equals(qName)) {
                this.text = new StringBuffer();
                String id = atts.getValue("changeSetId");
                if (id != null) {
                    String path = atts.getValue("changeSetPath");
                    if (path == null) {
                        path = this.databaseChangeLog.getFilePath();
                    }
                    String author = atts.getValue("changeSetAuthor");
                    ChangeSet changeSet = this.databaseChangeLog.getChangeSet(path, author, id);
                    if (changeSet == null) {
                        throw new SAXException("Could not find changeSet to use for rollback: " + path + ":" + author
                                + ":" + id);
                    } else {
                        for (Change change : changeSet.getChanges()) {
                            this.changeSet.addRollbackChange(change);
                        }
                    }
                }
                this.inRollback = true;
            } else if ("preConditions".equals(qName)) {
                this.rootPrecondition = new Preconditions();
                this.rootPrecondition.setOnFail(StringUtils.trimToNull(atts.getValue("onFail")));
                this.rootPrecondition.setOnError(StringUtils.trimToNull(atts.getValue("onError")));
                this.rootPrecondition.setOnUpdateSQL(StringUtils.trimToNull(atts.getValue("onUpdateSQL")));
                this.preconditionLogicStack.push(this.rootPrecondition);
            } else if ((this.currentPrecondition != null)
                    && (this.currentPrecondition instanceof CustomPreconditionWrapper) && qName.equals("param")) {
                ((CustomPreconditionWrapper) this.currentPrecondition).setParam(atts.getValue("name"),
                        atts.getValue("value"));
            } else if (this.rootPrecondition != null) {
                this.currentPrecondition = PreconditionFactory.getInstance().create(qName);

                for (int i = 0; i < atts.getLength(); i++) {
                    String attributeName = atts.getQName(i);
                    String attributeValue = atts.getValue(i);
                    this.setProperty(this.currentPrecondition, attributeName, attributeValue);
                }
                this.preconditionLogicStack.peek().addNestedPrecondition(this.currentPrecondition);

                if (this.currentPrecondition instanceof PreconditionLogic) {
                    this.preconditionLogicStack.push(((PreconditionLogic) this.currentPrecondition));
                }

                if ("sqlCheck".equals(qName)) {
                    this.text = new StringBuffer();
                }
            } else if ("modifySql".equals(qName)) {
                this.inModifySql = true;
                if (StringUtils.trimToNull(atts.getValue("dbms")) != null) {
                    this.modifySqlDbmsList = StringUtils.splitAndTrim(atts.getValue("dbms"), ",");
                }
            } else if (this.inModifySql) {
                SqlVisitor sqlVisitor = SqlVisitorFactory.getInstance().create(qName);
                for (int i = 0; i < atts.getLength(); i++) {
                    String attributeName = atts.getQName(i);
                    String attributeValue = atts.getValue(i);
                    this.setProperty(sqlVisitor, attributeName, attributeValue);
                }
                sqlVisitor.setApplicableDbms(this.modifySqlDbmsList);

                this.changeSet.addSqlVisitor(sqlVisitor);
            } else if ((this.changeSet != null) && (this.change == null)) {
                this.change = ChangeFactory.getInstance().create(qName);
                this.change.setChangeSet(this.changeSet);
                this.text = new StringBuffer();
                if (this.change == null) {
                    throw new MigrationFailedException(this.changeSet, "Unknown change: " + qName);
                }
                this.change.setFileOpener(this.fileOpener);
                if (this.change instanceof CustomChangeWrapper) {
                    ((CustomChangeWrapper) this.change).setClassLoader(this.fileOpener.toClassLoader());
                }
                for (int i = 0; i < atts.getLength(); i++) {
                    String attributeName = atts.getQName(i);
                    String attributeValue = atts.getValue(i);
                    this.setProperty(this.change, attributeName, attributeValue);
                }
                this.change.setUp();
            } else if ((this.change != null) && "column".equals(qName)) {
                ColumnConfig column;
                if (this.change instanceof LoadDataChange) {
                    column = new LoadDataColumnConfig();
                } else {
                    column = new ColumnConfig();
                }
                for (int i = 0; i < atts.getLength(); i++) {
                    String attributeName = atts.getQName(i);
                    String attributeValue = atts.getValue(i);
                    this.setProperty(column, attributeName, attributeValue);
                }
                if (this.change instanceof ChangeWithColumns) {
                    ((ChangeWithColumns) this.change).addColumn(column);
                } else {
                    throw new RuntimeException("Unexpected column tag for " + this.change.getClass().getName());
                }
            } else if ((this.change != null) && "constraints".equals(qName)) {
                ConstraintsConfig constraints = new ConstraintsConfig();
                for (int i = 0; i < atts.getLength(); i++) {
                    String attributeName = atts.getQName(i);
                    String attributeValue = atts.getValue(i);
                    this.setProperty(constraints, attributeName, attributeValue);
                }
                ColumnConfig lastColumn;
                if (this.change instanceof AddColumnChange) {
                    lastColumn = ((AddColumnChange) this.change).getLastColumn();
                } else if (this.change instanceof CreateTableChange) {
                    lastColumn =
                            ((CreateTableChange) this.change).getColumns().get(
                                    ((CreateTableChange) this.change).getColumns().size() - 1);
                } else if (this.change instanceof ModifyColumnChange) {
                    lastColumn =
                            ((ModifyColumnChange) this.change).getColumns().get(
                                    ((ModifyColumnChange) this.change).getColumns().size() - 1);
                } else {
                    throw new RuntimeException("Unexpected change: " + this.change.getClass().getName());
                }
                lastColumn.setConstraints(constraints);
            } else if ("param".equals(qName)) {
                if (this.change instanceof CustomChangeWrapper) {
                    if (atts.getValue("value") == null) {
                        this.paramName = atts.getValue("name");
                        this.text = new StringBuffer();
                    } else {
                        ((CustomChangeWrapper) this.change).setParam(atts.getValue("name"), atts.getValue("value"));
                    }
                } else {
                    throw new MigrationFailedException(this.changeSet, "'param' unexpected in " + qName);
                }
            } else if ("where".equals(qName)) {
                this.text = new StringBuffer();
            } else if ("property".equals(qName)) {
                if (StringUtils.trimToNull(atts.getValue("file")) == null) {
                    this.setParameterValue(atts.getValue("name"), atts.getValue("value"));
                } else {
                    Properties props = new Properties();
                    InputStream propertiesStream = this.fileOpener.getResourceAsStream(atts.getValue("file"));
                    if (propertiesStream == null) {
                        this.log.info("Could not open properties file " + atts.getValue("file"));
                    } else {
                        props.load(propertiesStream);

                        for (Map.Entry entry : props.entrySet()) {
                            this.setParameterValue(entry.getKey().toString(), entry.getValue().toString());
                        }
                    }
                }
            } else if ((this.change instanceof ExecuteShellCommandChange) && "arg".equals(qName)) {
                ((ExecuteShellCommandChange) this.change).addArg(atts.getValue("value"));
            } else {
                throw new MigrationFailedException(this.changeSet, "Unexpected tag: " + qName);
            }
        } catch (Exception e) {
            this.log.log(Level.SEVERE, "Error thrown as a SAXException: " + e.getMessage(), e);
            e.printStackTrace();
            throw new SAXException(e);
        }
    }

    /**
     * @param fileUrl
     * @throws IOException
     * @throws LiquibaseException
     * @since
     */
    private boolean handleJar(URL fileUrl) throws IOException, LiquibaseException {
        String fullPath = fileUrl.getPath();
        // o caminho vem no formato xxx.jar!/changelog/abc/
        String[] paths = fullPath.split("!");
        // tirando file:pq se nao da pau na hora de abrir o jar
        String jarPath = paths[0].replaceAll("file:", "");
        // Tirando primeira /
        String changeLogPath = paths[1].substring(1);
        JarFile jfile = new JarFile(jarPath);
        Enumeration<?> e = jfile.entries();
        SortedSet<String> fileNames = new TreeSet<String>();
        while (e.hasMoreElements()) {
            ZipEntry entry = (ZipEntry) e.nextElement();
            String path = entry.getName();
            // pegar os zipentries dentro do changelogpath, tirando ele mesmo.
            if ((path).startsWith(changeLogPath) && !path.equals(changeLogPath)) {
                String fileName = path.substring(path.lastIndexOf("/") + 1);
                fileNames.add(fileName);
            }
        }
        return this.incluiDiretorio(changeLogPath, fileNames);
    }

    /**
     * @param pathName
     * @param foundResource
     * @param orderedFiles
     * @return
     * @throws LiquibaseException
     * @since
     */
    private boolean incluiDiretorio(String pathName, SortedSet<String> orderedFiles) throws LiquibaseException {
        boolean foundResource = false;
        for (String fileName : orderedFiles) {
            if (this.handleIncludedChangeLog(pathName + fileName, false, this.databaseChangeLog.getPhysicalFilePath())) {
                foundResource = true;
            }
        }
        return foundResource;
    }

    protected boolean handleIncludedChangeLog(String fileName, boolean isRelativePath, String relativeBaseFileName)
            throws LiquibaseException {
        if (!(fileName.endsWith(".xml") || fileName.endsWith(".sql"))) {
            this.log.finest(relativeBaseFileName + "/" + fileName + " is not a recognized file type");
            return false;
        }

        if (isRelativePath) {
            String path = this.searchPath(relativeBaseFileName);
            fileName = new StringBuilder(path).append(fileName).toString();
        }
        DatabaseChangeLog changeLog = new ChangeLogParser(this.changeLogParameters).parse(fileName, this.fileOpener);
        AndPrecondition preconditions = changeLog.getPreconditions();
        if (preconditions != null) {
            if (null == this.databaseChangeLog.getPreconditions()) {
                this.databaseChangeLog.setPreconditions(new Preconditions());
            }
            this.databaseChangeLog.getPreconditions().addNestedPrecondition(preconditions);
        }
        for (ChangeSet changeSet : changeLog.getChangeSets()) {
            this.databaseChangeLog.addChangeSet(changeSet);
        }

        return true;
    }

    private String searchPath(String relativeBaseFileName) {
        if (relativeBaseFileName == null) {
            return null;
        }
        int lastSeparatePosition = relativeBaseFileName.lastIndexOf(LIQUIBASE_FILE_SEPARATOR);
        if (lastSeparatePosition >= 0) {
            return relativeBaseFileName.substring(0, lastSeparatePosition + 1);
        }
        return relativeBaseFileName;
    }

    private void setProperty(Object object, String attributeName, String attributeValue)
            throws IllegalAccessException, InvocationTargetException, CustomChangeException {
        ExpressionExpander expressionExpander = new ExpressionExpander(this.changeLogParameters);
        if (object instanceof CustomChangeWrapper) {
            if (attributeName.equals("class")) {
                ((CustomChangeWrapper) object).setClass(expressionExpander.expandExpressions(attributeValue));
            } else {
                ((CustomChangeWrapper) object).setParam(attributeName,
                        expressionExpander.expandExpressions(attributeValue));
            }
        } else {
            ObjectUtil.setProperty(object, attributeName, expressionExpander.expandExpressions(attributeValue));
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        String textString = null;
        if ((this.text != null) && (this.text.length() > 0)) {
            textString =
                    new ExpressionExpander(this.changeLogParameters).expandExpressions(StringUtils.trimToNull(this.text
                            .toString()));
        }

        try {
            if (this.rootPrecondition != null) {
                if ("preConditions".equals(qName)) {
                    if (this.changeSet == null) {
                        this.databaseChangeLog.setPreconditions(this.rootPrecondition);
                        this.handlePreCondition(this.rootPrecondition);
                    } else {
                        this.changeSet.setPreconditions(this.rootPrecondition);
                    }
                    this.rootPrecondition = null;
                } else if ("and".equals(qName)) {
                    this.preconditionLogicStack.pop();
                    this.currentPrecondition = null;
                } else if ("or".equals(qName)) {
                    this.preconditionLogicStack.pop();
                    this.currentPrecondition = null;
                } else if ("not".equals(qName)) {
                    this.preconditionLogicStack.pop();
                    this.currentPrecondition = null;
                } else if (qName.equals("sqlCheck")) {
                    ((SqlPrecondition) this.currentPrecondition).setSql(textString);
                    this.currentPrecondition = null;
                } else if (qName.equals("customPrecondition")) {
                    ((CustomPreconditionWrapper) this.currentPrecondition).setClassLoader(this.fileOpener
                            .toClassLoader());
                }

            } else if ((this.changeSet != null) && "rollback".equals(qName)) {
                this.changeSet.addRollBackSQL(textString);
                this.inRollback = false;
            } else if ((this.change != null) && (this.change instanceof RawSQLChange) && "comment".equals(qName)) {
                ((RawSQLChange) this.change).setComments(textString);
                this.text = new StringBuffer();
            } else if ((this.change != null) && "where".equals(qName)) {
                if (this.change instanceof UpdateDataChange) {
                    ((UpdateDataChange) this.change).setWhereClause(textString);
                } else if (this.change instanceof DeleteDataChange) {
                    ((DeleteDataChange) this.change).setWhereClause(textString);
                } else {
                    throw new RuntimeException("Unexpected change type: " + this.change.getClass().getName());
                }
                this.text = new StringBuffer();
            } else if ((this.change != null) && (this.change instanceof CreateProcedureChange)
                    && "comment".equals(qName)) {
                ((CreateProcedureChange) this.change).setComments(textString);
                this.text = new StringBuffer();
            } else if ((this.change != null) && (this.change instanceof CustomChangeWrapper)
                    && (this.paramName != null) && "param".equals(qName)) {
                ((CustomChangeWrapper) this.change).setParam(this.paramName, textString);
                this.text = new StringBuffer();
                this.paramName = null;
            } else if ((this.changeSet != null) && "comment".equals(qName)) {
                this.changeSet.setComments(textString);
                this.text = new StringBuffer();
            } else if ((this.changeSet != null) && "changeSet".equals(qName)) {
                this.handleChangeSet(this.changeSet);
                this.changeSet = null;
            } else if ((this.change != null) && qName.equals("column") && (textString != null)) {
                if (this.change instanceof InsertDataChange) {
                    List<ColumnConfig> columns = ((InsertDataChange) this.change).getColumns();
                    columns.get(columns.size() - 1).setValue(textString);
                } else if (this.change instanceof UpdateDataChange) {
                    List<ColumnConfig> columns = ((UpdateDataChange) this.change).getColumns();
                    columns.get(columns.size() - 1).setValue(textString);
                } else {
                    throw new RuntimeException("Unexpected column with text: " + textString);
                }
                this.text = new StringBuffer();
            } else if ((this.change != null) && qName.equals(this.change.getTagName())) {
                if (textString != null) {
                    if (this.change instanceof RawSQLChange) {
                        ((RawSQLChange) this.change).setSql(textString);
                    } else if (this.change instanceof CreateProcedureChange) {
                        ((CreateProcedureChange) this.change).setProcedureBody(textString);
                    } else if (this.change instanceof CreateViewChange) {
                        ((CreateViewChange) this.change).setSelectQuery(textString);
                    } else if (this.change instanceof StopChange) {
                        ((StopChange) this.change).setMessage(textString);
                    } else {
                        throw new RuntimeException("Unexpected text in " + this.change.getTagName());
                    }
                }
                this.text = null;
                if (this.inRollback) {
                    this.changeSet.addRollbackChange(this.change);
                } else {
                    this.changeSet.addChange(this.change);
                }
                this.change = null;
            } else if ((this.changeSet != null) && "validCheckSum".equals(qName)) {
                this.changeSet.addValidCheckSum(this.text.toString());
                this.text = null;
            } else if ("modifySql".equals(qName)) {
                this.inModifySql = false;
                this.modifySqlDbmsList = null;
            }
        } catch (Exception e) {
            this.log.log(Level.SEVERE, "Error thrown as a SAXException: " + e.getMessage(), e);
            throw new SAXException(this.databaseChangeLog.getPhysicalFilePath() + ": " + e.getMessage(), e);
        }
    }

    protected void handlePreCondition(@SuppressWarnings("unused") Precondition precondition) {
        this.databaseChangeLog.setPreconditions(this.rootPrecondition);
    }

    protected void handleChangeSet(ChangeSet changeSet) {
        this.databaseChangeLog.addChangeSet(changeSet);
    }

    @Override
    public void characters(char ch[], int start, int length) throws SAXException {
        if (this.text != null) {
            this.text.append(new String(ch, start, length));
        }
    }

    public Object getParameterValue(String paramter) {
        return this.changeLogParameters.get(paramter);
    }

    public void setParameterValue(String paramter, Object value) {
        if (!this.changeLogParameters.containsKey(paramter)) {
            this.changeLogParameters.put(paramter, value);
        }
    }

    /**
     * Wrapper for Attributes that expands the value as needed
     */
    private class ExpandingAttributes implements Attributes {

        private Attributes attributes;

        private ExpandingAttributes(Attributes attributes) {
            this.attributes = attributes;
        }

        public int getLength() {
            return this.attributes.getLength();
        }

        public String getURI(int index) {
            return this.attributes.getURI(index);
        }

        public String getLocalName(int index) {
            return this.attributes.getLocalName(index);
        }

        public String getQName(int index) {
            return this.attributes.getQName(index);
        }

        public String getType(int index) {
            return this.attributes.getType(index);
        }

        public String getValue(int index) {
            return this.attributes.getValue(index);
        }

        public int getIndex(String uri, String localName) {
            return this.attributes.getIndex(uri, localName);
        }

        public int getIndex(String qName) {
            return this.attributes.getIndex(qName);
        }

        public String getType(String uri, String localName) {
            return this.attributes.getType(uri, localName);
        }

        public String getType(String qName) {
            return this.attributes.getType(qName);
        }

        public String getValue(String uri, String localName) {
            return new ExpressionExpander(XMLChangeLogHandler.this.changeLogParameters)
                    .expandExpressions(this.attributes.getValue(uri, localName));
        }

        public String getValue(String qName) {
            return new ExpressionExpander(XMLChangeLogHandler.this.changeLogParameters)
                    .expandExpressions(this.attributes.getValue(qName));
        }
    }
}
