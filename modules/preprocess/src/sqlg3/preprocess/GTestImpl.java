package sqlg3.preprocess;

import sqlg3.core.SQLGException;
import sqlg3.runtime.GTest;
import sqlg3.runtime.Parameter;
import sqlg3.runtime.RuntimeMapper;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class GTestImpl extends GTest {

    private final Map<ParamName, Class<?>> paramTypeMap = new HashMap<>();
    private Map<ParamName, List<ParamCutPaste>> bindMap;

    private String displayEntryName;
    private final Map<Statement, String> stmtMap = new HashMap<>();

    final Connection connection;
    final SqlChecker checker;
    private final Mapper mapper;
    final RuntimeMapper mappers;
    private final Map<Class<?>, List<RowTypeInfo>> generatedIn;
    private final Map<Class<?>, List<RowTypeInfo>> generatedOut;

    GTestImpl(Connection connection, SqlChecker checker, Mapper mapper, RuntimeMapper mappers,
              Map<Class<?>, List<RowTypeInfo>> generatedIn, Map<Class<?>, List<RowTypeInfo>> generatedOut) {
        this.connection = connection;
        this.checker = checker;
        this.mapper = mapper;
        this.mappers = mappers;
        this.generatedIn = generatedIn;
        this.generatedOut = generatedOut;
    }

    private Object getValue(Class<?> cls) {
        if (cls != null)
            return getTestObject(cls);
        return null;
    }

    @Override
    public <T> T getNullInterface(Class<T> iface) {
        Object ret = Proxy.newProxyInstance(
            iface.getClassLoader(),
            new Class[] {iface},
            (proxy, method, args) -> getValue(method.getReturnType())
        );
        return iface.cast(ret);
    }

    @Override
    public Object getTestObject(Class<?> paramType) {
        return mapper.getTestObject(paramType);
    }

    @Override
    public void getRowTypeFields(Class<?> rowType, ResultSet rs, boolean meta) throws SQLException {
        List<ColumnInfo> columns = mapper.getFields(rs.getMetaData(), meta, mappers);
        RowTypeInfo info = new RowTypeInfo(displayEntryName, columns, meta);

        Map<Class<?>, List<RowTypeInfo>> generated = rowType.getDeclaringClass() == null ? generatedOut : generatedIn;
        generated.computeIfAbsent(rowType, k -> new ArrayList<>()).add(info);
    }

    private ColumnInfo getOneColumn(ResultSet rs) throws SQLException {
        List<ColumnInfo> columns = mapper.getFields(rs.getMetaData(), false, mappers);
        if (columns.size() != 1) {
            throw new SQLException("More than one column in result set");
        }
        return columns.get(0);
    }

    @Override
    public void checkOneColumn(ResultSet rs, Class<?> cls) throws SQLException {
        ColumnInfo col1 = getOneColumn(rs);
        if (!cls.equals(col1.type)) {
            if (RowTypeInfo.getWiderOf(cls, col1.type) == null) {
                throw new SQLException("Column is of type " + col1.type.getCanonicalName() + ", but " + cls.getCanonicalName() + " required");
            }
        }
    }

    @Override
    public void checkSql(PreparedStatement stmt) throws SQLException {
        String sql = stmtMap.get(stmt);
        checker.checkSql(connection, stmt, sql);
    }

    @Override
    public void statementCreated(Statement stmt, String sql) {
        if (sql != null) {
            stmtMap.put(stmt, sql);
        }
    }

    @Override
    public Class<?> setParamType(String paramId, Class<?> paramClass) {
        ParamName paramName = ParamName.fromId(paramId);
        Class<?> cls = mapper.getParameterClass(paramClass);
        List<ParamCutPaste> params = bindMap.get(paramName);
        if (params != null) {
            String className = ClassUtils.getClassName(cls);
            for (ParamCutPaste param : params) {
                if (param.out) {
                    param.replaceTo = "outP(" + param.param + ")";
                } else {
                    param.replaceTo = "inP(" + param.param + ", " + className + ".class)";
                }
            }
        }
        Class<?> existingClass = paramTypeMap.get(paramName);
        if (existingClass != null) {
            if (!cls.equals(existingClass)) {
                throw new SQLGException("Parameter " + paramName + " has conflicting type definitions: was " + existingClass + ", became " + cls);
            }
        }
        paramTypeMap.put(paramName, cls);
        return cls;
    }

    @Override
    public void checkStoredProcName(String procNameToCall, Parameter[] parameters) throws SQLException {
        checker.checkStoredProcName(connection, procNameToCall, parameters);
    }

    @Override
    public void checkSequenceExists(String sequence) throws SQLException {
        checker.checkSequenceExists(connection, sequence);
    }

    void startClass(Map<ParamName, List<ParamCutPaste>> bindMap) {
        this.bindMap = bindMap;
        this.paramTypeMap.clear();
    }

    Map<ParamName, Class<?>> endClass() {
        return paramTypeMap;
    }

    void startCall(String displayEntryName) {
        this.displayEntryName = displayEntryName;
        this.stmtMap.clear();
    }
}
