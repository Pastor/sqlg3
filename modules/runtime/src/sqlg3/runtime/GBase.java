package sqlg3.runtime;

import sqlg3.core.IDBCommon;
import sqlg3.core.ISimpleTransaction;
import sqlg3.core.SQLGException;
import sqlg3.runtime.queries.QueryParser;

import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Base class for all classes which are processed by preprocessor. Wraps access to JDBC methods allowing
 * preprocessor to intercept them and extract required information.
 * <p>
 * This class itself is not thread-safe, so it cannot be used by more than
 * one thread at a time. Use wrappers generated by preprocessor to access business methods.
 */
@SuppressWarnings("UnusedDeclaration")
public class GBase implements ISimpleTransaction {

    /**
     * Use it for {@code autoKeys} parameter of {@link #prepareStatementKey} to
     * retrieve all generated columns.
     *
     * @see sqlg3.annotations.PrepareKey#value()
     */
    public static final String[] ALL_KEYS = new String[0];

    static GTest test = null;

    private final GContext ctx;

    /**
     * Constructor. Usually it is called by generated wrappers.
     *
     * @param ctx context
     */
    public GBase(GContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Returns true if method is called at preprocessing time (false at application run time).
     */
    public static boolean isTesting() {
        return test != null;
    }

    private Connection getConnection() throws SQLException {
        return ctx.transaction.getConnection();
    }

    /**
     * Access to raw JDBC connection. Can be used <b>only</b> at application run time, not at preprocess time,
     * so check {@link #isTesting} before calling this method.
     */
    public final Connection getJdbcConnection() throws SQLException {
        if (test != null)
            throw new IllegalStateException("Cannot use Connection in preprocess mode");
        return getConnection();
    }

    private void setSql(String sql, Parameter[] params) {
        ctx.call.setSql(sql, params);
    }

    ///////////////////////////////// Query piece creation /////////////////////////////////

    /**
     * Used for preprocessor-generated query pieces (see {@link sqlg3.annotations.Query})
     */
    public static QueryPiece createQueryPiece(QueryPiece query) {
        return query;
    }

    /**
     * Creates SQL query piece containing query text and its parameters.
     * Example:
     * <pre>
     * QueryPiece piece = createQueryPiece(" AND type_id = ?", in(typeId, Long.class));
     * </pre>
     * It is more convenient to use {@link sqlg3.annotations.Query} annotation to generate such pieces than
     * to use this method manually.
     * <p>
     * After piece is created, it can be used to build large query (see {@link QueryBuilder}) or execute query
     * (see {@link #prepareStatement(QueryPiece)})
     *
     * @param sql    query text, possibly containing references to parameters in the form of {@code ?}
     * @param params query parameters, see {@link #in}
     */
    public static QueryPiece createQueryPiece(CharSequence sql, Parameter... params) {
        return new QueryPiece(sql, params);
    }

    ///////////////////////////////// Statement preparation /////////////////////////////////

    private interface StatementFactory<T extends PreparedStatement> {

        T create(Connection connection, String sql) throws SQLException;
    }

    private <T extends PreparedStatement> T doPrepareAnyStatement(String sql, Parameter[] params, StatementFactory<T> factory) throws SQLException {
        Connection connection = getConnection();
        T stmt = factory.create(connection, sql);
        if (test != null) {
            test.statementCreated(stmt, sql);
        }
        ctx.call.statementCreated(stmt, params);
        Parameter.setParameters(ctx.global.mappers, stmt, params);
        return stmt;
    }

    private PreparedStatement doPrepareStatement(String[] autoKeys, String unparsedSql, Parameter[] params) throws SQLException {
        setSql(unparsedSql, params);
        String parsedSql = QueryParser.parseQuery(unparsedSql);
        if (autoKeys == null) {
            return doPrepareAnyStatement(parsedSql, params, Connection::prepareStatement);
        } else {
            return doPrepareAnyStatement(parsedSql, params, (connection, sql) -> {
                if (autoKeys.length > 0) {
                    DatabaseMetaData meta = connection.getMetaData();
                    String[] autoColumns;
                    if (meta.storesUpperCaseIdentifiers()) {
                        autoColumns = new String[autoKeys.length];
                        for (int i = 0; i < autoKeys.length; i++) {
                            autoColumns[i] = autoKeys[i].toUpperCase();
                        }
                    } else if (meta.storesLowerCaseIdentifiers()) {
                        autoColumns = new String[autoKeys.length];
                        for (int i = 0; i < autoKeys.length; i++) {
                            autoColumns[i] = autoKeys[i].toLowerCase();
                        }
                    } else {
                        autoColumns = autoKeys;
                    }
                    return connection.prepareStatement(sql, autoColumns);
                } else {
                    return connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                }
            });
        }
    }

    /**
     * Creates prepared statement from SQL query and its parameters.
     * Later this statement can be executed by {@link #executeUpdate} or by one of many selection methods.
     * Example:
     * <pre>
     * PreparedStatement stmt = prepareStatement("SELECT value FROM table WHERE id = ?", in(id, Long.class));
     * int result = singleRowQueryReturningInt(stmt);
     * </pre>
     * It is more convenient to use {@link sqlg3.annotations.Prepare} annotation to generate such queries than
     * to use this method manually.
     *
     * @param sql    query text, possibly containing references to parameters in the form of {@code ?}
     * @param params query parameters, see {@link #in}
     */
    public final PreparedStatement prepareStatement(String sql, Parameter... params) throws SQLException {
        return doPrepareStatement(null, sql, params);
    }

    /**
     * Creates prepared statement with auto-generated keys from SQL query and its parameters.
     * Later this statement can be executed by {@link #executeUpdate} or by one of many selection methods.
     * Example:
     * <pre>
     * PreparedStatement stmt = prepareStatementKey(new String[] {"id"}, "INSERT INTO table (name) values ('test')");
     * executeUpdate(stmt);
     * int generatedId = getGeneratedKeys()[0].intValue();
     * </pre>
     * It is more convenient to use {@link sqlg3.annotations.PrepareKey} annotation to generate such queries than
     * to use this method manually.
     *
     * @param autoKeys array of column names which are auto-generated.
     *                 Pass {@link #ALL_KEYS} to retrieve all generated columns (does not work on some DBs).
     * @param sql      query text, possibly containing references to parameters in the form of {@code ?}
     * @param params   query parameters, see {@link #in}
     */
    public final PreparedStatement prepareStatementKey(String[] autoKeys, String sql, Parameter... params) throws SQLException {
        return doPrepareStatement(autoKeys, sql, params);
    }

    /**
     * Creates prepared statement from a piece containing SQL query and its parameters.
     * Later this statement can be executed by {@link #executeUpdate} or by one of many selection methods.
     * Example:
     * <pre>
     * PreparedStatement stmt = prepareStatement("SELECT value FROM table WHERE id = ?", in(id, Long.class));
     * int result = singleRowQueryReturningInt(stmt);
     * </pre>
     * It is more convenient to use {@link sqlg3.annotations.Prepare} annotation to generate such queries than
     * to use this method manually.
     *
     * @param query query piece containing SQL query and its parameters
     */
    public final PreparedStatement prepareStatement(QueryPiece query) throws SQLException {
        return doPrepareStatement(null, query.sql, query.data);
    }

    /**
     * Creates prepared statement with auto-generated keys from a piece containing SQL query and its parameters.
     * Later this statement can be executed by {@link #executeUpdate} or by one of many selection methods.
     * Example:
     * <pre>
     * PreparedStatement stmt = prepareStatementKey(new String[] {"id"}, "INSERT INTO table (name) values ('test')");
     * executeUpdate(stmt);
     * int generatedId = getGeneratedKeys()[0].intValue();
     * </pre>
     * It is more convenient to use {@link sqlg3.annotations.PrepareKey} annotation to generate such queries than
     * to use this method manually.
     *
     * @param autoKeys array of column names which are auto-generated.
     *                 Pass {@link #ALL_KEYS} to retrieve all generated columns (does not work on some DBs).
     * @param query    query piece containing SQL query and its parameters
     */
    public final PreparedStatement prepareStatementKey(String[] autoKeys, QueryPiece query) throws SQLException {
        return doPrepareStatement(autoKeys, query.sql, query.data);
    }

    /**
     * Creates CallableStatement for stored procedure (or PL/SQL block) call.
     *
     * @param sql stored procedure to call.
     *            Syntax is the same as for {@link Connection#prepareCall(String)}:
     *            <ul>
     *            <li>{ call proc_name(params) } or { ? = func_name(params) }
     *            <li>begin ... end;
     *            </ul>
     */
    public final CallableStatement prepareCall(String sql, Parameter... params) throws SQLException {
        setSql(sql, params);
        return doPrepareAnyStatement(sql, params, Connection::prepareCall);
    }

    public final CallableStatement prepareCall(QueryPiece query) throws SQLException {
        return prepareCall(query.sql, query.data);
    }

    /**
     * For internal use. Do not use this method at runtime, it throws exception when not preprocessing.
     */
    public static Parameter inP(Object value, String paramId) {
        if (test != null) {
            Class<?> cls = test.setParamType(paramId, value.getClass());
            return Parameter.in(value, cls);
        } else {
            throw new SQLGException("No type is defined for parameter " + paramId);
        }
    }

    /**
     * Same as {@link #in} but generated by preprocessor.
     */
    public static <T> Parameter inP(T value, Class<T> cls) {
        return in(value, cls);
    }

    /**
     * Creates parameter for prepared statement.
     *
     * @param value parameter value. Can be null.
     * @param cls   parameter class. Should be not null.
     */
    public static <T> Parameter in(T value, Class<T> cls) {
        return Parameter.in(value, cls);
    }

    /**
     * For internal use. Do not use this method at runtime, it throws exception when not preprocessing.
     */
    public static Parameter outP(Object value, String paramId) {
        if (test != null) {
            if (value == null || !value.getClass().isArray())
                throw new SQLGException("Parameter should be an array");
            test.setParamType(paramId, value.getClass().getComponentType());
            return Parameter.out(value);
        } else {
            throw new SQLGException("No type is defined for out parameter " + paramId);
        }
    }

    /**
     * Same as {@link #out} but generated by preprocessor.
     */
    public static Parameter outP(Object value) {
        return out(value);
    }

    /**
     * Creates OUT parameter for stored procedure call.
     *
     * @param value Should be an array with at least one element to store output value.
     *              Should be not null.
     */
    public static Parameter out(Object value) {
        if (test != null) {
            if (value == null || !value.getClass().isArray())
                throw new SQLGException("Parameter should be an array");
        }
        return Parameter.out(value);
    }

    /**
     * Binds prepared statement parameters to specific values.
     *
     * @param st SQL statement
     * @param in parameter values
     */
    public final void setParameters(PreparedStatement st, Parameter... in) throws SQLException {
        Parameter.setParameters(ctx.global.mappers, st, in);
    }

    /**
     * Binds single prepared statement parameter to specific value.
     *
     * @param st SQL statement
     * @param index index of the parameter (from 1)
     * @param value parameter value
     * @param cls parameter class
     */
    public final <T> void setParameter(PreparedStatement st, int index, T value, Class<T> cls) throws SQLException {
        Parameter.in(value, cls).set(ctx.global.mappers, st, index);
    }

    ///////////////////////////////// Sinlge and optional row statements /////////////////////////////////

    private static boolean checkNext(ResultSet rs, boolean optional) throws SQLException {
        boolean hasNext = rs.next();
        if (!hasNext) {
            if (optional) {
                return false;
            } else {
                throw new SQLException("No rows found");
            }
        } else {
            return true;
        }
    }

    private static void tooManyRows(ResultSet rs) throws SQLException {
        if (rs.next())
            throw new SQLException("Too many rows");
    }

    private <T> T singleOrOptionalRowQueryReturningT(Class<T> cls, PreparedStatement stmt, boolean optional) throws SQLException {
        TypeMapper<T> mapper = getMapper(cls);
        try (ResultSet rs = stmt.executeQuery()) {
            if (test != null) {
                test.checkOneColumn(rs, cls);
                return cls.cast(test.getTestObject(cls));
            } else {
                if (!checkNext(rs, optional))
                    return null;
                T ret = mapper.fetch(rs, 1);
                tooManyRows(rs);
                return ret;
            }
        }
    }

    /**
     * Executes select query, which should return one row and one column (more or less than
     * one row raises runtime exception, more or less than one column raises
     * preprocess-time exception).
     * Closes statement after execution.
     *
     * @param cls class with user-defined mapping (see {@link RuntimeMapper})
     */
    public final <T> T singleRowQueryReturning(Class<T> cls, PreparedStatement stmt) throws SQLException {
        return singleOrOptionalRowQueryReturningT(cls, stmt, false);
    }

    /**
     * Executes select query, which should return one row and one column (more or less than
     * one row raises runtime exception, more or less than one column raises
     * preprocess-time exception). Result is returned as a single <code>int</code>. NULLs are returned as zeroes.
     * Closes statement after execution.
     */
    public final int singleRowQueryReturningInt(PreparedStatement stmt) throws SQLException {
        Integer value = singleRowQueryReturning(Integer.class, stmt);
        return value == null ? 0 : value.intValue();
    }

    /**
     * Executes select query, which should return one row and one column (more or less than
     * one row raises runtime exception, more or less than one column raises
     * preprocess-time exception). Result is returned as a single <code>long</code>. NULLs are returned as zeroes.
     * Closes statement after execution.
     */
    public final long singleRowQueryReturningLong(PreparedStatement stmt) throws SQLException {
        Long value = singleRowQueryReturning(Long.class, stmt);
        return value == null ? 0L : value.longValue();
    }

    /**
     * Executes select query, which should return one row and one column (more or less than
     * one row raises runtime exception, more or less than one column raises
     * preprocess-time exception). Result is returned as a single <code>double</code>. NULLs are returned as zeroes.
     * Closes statement after execution.
     */
    public final double singleRowQueryReturningDouble(PreparedStatement stmt) throws SQLException {
        Double value = singleRowQueryReturning(Double.class, stmt);
        return value == null ? 0.0 : value.doubleValue();
    }

    /**
     * Same as {@link #singleRowQueryReturning(Class, PreparedStatement)} but returns
     * null when no rows found.
     *
     * @param cls class with user-defined mapping (see {@link RuntimeMapper})
     */
    public final <T> T optionalRowQueryReturning(Class<T> cls, PreparedStatement stmt) throws SQLException {
        return singleOrOptionalRowQueryReturningT(cls, stmt, true);
    }

    ///////////////////////////////// Column statements /////////////////////////////////

    /**
     * Executes select query returning single column of T.
     * Closes statement after execution.
     *
     * @param cls class with user-defined mapping (see {@link RuntimeMapper})
     */
    public final <T> List<T> columnOf(Class<T> cls, PreparedStatement stmt) throws SQLException {
        TypeMapper<T> mapper = getMapper(cls);
        List<T> list = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery()) {
            if (test != null) {
                test.checkOneColumn(rs, cls);
            } else {
                while (rs.next()) {
                    list.add(mapper.fetch(rs, 1));
                }
            }
        }
        return list;
    }

    /**
     * Executes select query returning single column of <code>int</code>.
     * Closes statement after execution.
     */
    public final int[] columnOfInt(PreparedStatement stmt) throws SQLException {
        List<Integer> list = columnOf(Integer.class, stmt);
        int[] ret = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Integer value = list.get(i);
            ret[i] = value == null ? 0 : value.intValue();
        }
        return ret;
    }

    /**
     * Executes select query returning single column of <code>long</code>.
     * Closes statement after execution.
     */
    public final long[] columnOfLong(PreparedStatement stmt) throws SQLException {
        List<Long> list = columnOf(Long.class, stmt);
        long[] ret = new long[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Long value = list.get(i);
            ret[i] = value == null ? 0L : value.longValue();
        }
        return ret;
    }

    /**
     * Executes select query returning single column of <code>double</code>.
     * Closes statement after execution.
     */
    public final double[] columnOfDouble(PreparedStatement stmt) throws SQLException {
        List<Double> list = columnOf(Double.class, stmt);
        double[] ret = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Double value = list.get(i);
            ret[i] = value == null ? 0.0 : value.doubleValue();
        }
        return ret;
    }

    ///////////////////////////////// Class statements /////////////////////////////////

    private <T> T fetchFromResultSet(Class<T> rowType, ResultSet rs, boolean meta) throws SQLException {
        RowTypeFactory<T> factory = ctx.global.getRowTypeFactory(rowType, meta);
        return factory.fetch(ctx.global.mappers, rs);
    }

    private <T> T singleOrOptionalRowQuery(PreparedStatement stmt, boolean optional, Class<T> rowType) throws SQLException {
        try (ResultSet rs = stmt.executeQuery()) {
            boolean meta = false;
            if (test != null) {
                test.getRowTypeFields(rowType, rs, meta);
                return null;
            } else {
                if (!checkNext(rs, optional))
                    return null;
                T ret = fetchFromResultSet(rowType, rs, meta);
                tooManyRows(rs);
                return ret;
            }
        }
    }

    /**
     * Executes select query, which should return exactly one row (more or less than
     * one rows raises runtime exception).
     * Result is returned as an object which class implementation is generated by preprocessor.
     * Closes statement after execution.
     *
     * @param stmt SQL statement
     * @param rowType  row type class or interface generated by preprocessor
     */
    public final <T> T singleRowQuery(PreparedStatement stmt, Class<T> rowType) throws SQLException {
        return singleOrOptionalRowQuery(stmt, false, rowType);
    }

    /**
     * Same as {@link #singleRowQuery(PreparedStatement, Class)} but returns
     * null when no rows found.
     */
    public final <T> T optionalRowQuery(PreparedStatement stmt, Class<T> rowType) throws SQLException {
        return singleOrOptionalRowQuery(stmt, true, rowType);
    }

    /**
     * Executes select query returning multiple (zero or more) rows.
     * Result is returned as a list of objects which class implementation is generated by preprocessor.
     * Closes statement after execution.
     *
     * @param stmt SQL statement
     * @param rowType  row type class or interface generated by preprocessor
     */
    public final <T> List<T> multiRowQuery(PreparedStatement stmt, Class<T> rowType) throws SQLException {
        List<T> result = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery()) {
            boolean meta = false;
            if (test != null) {
                test.getRowTypeFields(rowType, rs, meta);
            } else {
                RowTypeFactory<T> factory = ctx.global.getRowTypeFactory(rowType, meta);
                while (rs.next()) {
                    T row = factory.fetch(ctx.global.mappers, rs);
                    result.add(row);
                }
            }
        }
        return result;
    }

    /**
     * Returns query ResultSet metadata as RowType object.
     */
    public final <T> T metaRowQuery(ResultSet rs, Class<T> rowType) throws SQLException {
        boolean meta = true;
        if (test != null) {
            test.getRowTypeFields(rowType, rs, meta);
            return null;
        } else {
            return fetchFromResultSet(rowType, rs, meta);
        }
    }

    /**
     * Returns query ResultSet metadata as RowType object.
     */
    public final <T> T metaRowQuery(PreparedStatement stmt, Class<T> rowType) throws SQLException {
        try (ResultSet rs = stmt.executeQuery()) {
            return metaRowQuery(rs, rowType);
        }
    }

    ///////////////////////////////// Executing DML /////////////////////////////////

    /**
     * Executes update/delete/insert SQL statement. This method should always be used
     * instead of {@link PreparedStatement#executeUpdate()} because the latter can modify
     * database state at preprocess phase.
     * <p>
     * This method does not close statement, so it can be called multiple times.
     * You don't have to close statement manually if it was created with {@link #prepareStatement(String, Parameter...)} or the like,
     * because all statements created with {@link GBase} methods are closed automatically after business method exit.
     *
     * @param stmt SQL statement
     * @return number of modified database rows
     */
    public static int executeUpdate(PreparedStatement stmt) throws SQLException {
        if (test != null) {
            test.checkSql(stmt);
            return 0;
        } else {
            return stmt.executeUpdate();
        }
    }

    /**
     * Returns array of auto-generated keys for insert/update statement. List of auto-generated
     * columns is provided by {@link #prepareStatementKey} <code>autoKeys</code>
     * parameter or by {@link sqlg3.annotations.PrepareKey} annotation value. Number of elements in array is equal to the number
     * of auto-generated columns.
     */
    public static Number[] getGeneratedKeys(PreparedStatement stmt) throws SQLException {
        if (test != null) {
            Number[] ret = new Number[10];
            Arrays.fill(ret, 0);
            return ret;
        } else {
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                ResultSetMetaData rsmd = rs.getMetaData();
                int count = rsmd.getColumnCount();
                rs.next();
                Number[] ret = new Number[count];
                for (int i = 0; i < count; i++) {
                    ret[i] = (Number) rs.getObject(i + 1);
                }
                return ret;
            }
        }
    }

    public static int getGeneratedInt(PreparedStatement ps) throws SQLException {
        return getGeneratedKeys(ps)[0].intValue();
    }

    public static long getGeneratedLong(PreparedStatement ps) throws SQLException {
        return getGeneratedKeys(ps)[0].longValue();
    }

    ///////////////////////////////// Executing calls /////////////////////////////////

    private static String getProcCallSql(String name, Parameter[] in) {
        StringBuilder buf = new StringBuilder("{ call " + name + "(");
        int argCount = in.length;
        for (int i = 0; i < argCount; i++) {
            if (i > 0)
                buf.append(", ");
            buf.append("?");
        }
        buf.append(") }");
        return buf.toString();
    }

    /**
     * Executes stored procedure or PL/SQL block.
     */
    public final void executeCall(CallableStatement cs) throws SQLException {
        if (test != null) {
            test.checkSql(cs);
        } else {
            cs.execute();
            Parameter.getOutParameters(ctx.global.mappers, cs, ctx.call.getParameters(cs));
        }
    }

    /**
     * Calls stored procedure.
     * Example:
     * <pre>
     * callStoredProc("trace", in(message, String.class));
     * </pre>
     *
     * @param name   Stored procedure name. SQL statement is generated by procedure name
     *               and parameters as <code>{ call name(in) }</code>.
     * @param params input/output parameters array (see {@link #in} and {@link #out}).
     */
    public final void callStoredProc(String name, Parameter... params) throws SQLException {
        if (test != null) {
            test.checkStoredProcName(name, params);
        }
        String sql = getProcCallSql(name, params);
        CallableStatement cs = prepareCall(sql, params);
        if (test == null) {
            cs.execute();
            Parameter.getOutParameters(ctx.global.mappers, cs, params);
        }
    }

    ///////////////////////////////// Utility methods /////////////////////////////////

    /**
     * Returns next number in sequence.
     *
     * @param sequence sequence name
     */
    public final long getNextId(String sequence) throws SQLException {
        if (test != null) {
            test.checkSequenceExists(sequence);
            return 0;
        }
        setSql(sequence + ".NEXTVAL", null);
        return ctx.global.db.getNextId(getConnection(), sequence);
    }

    /**
     * Returns data access interface generated by preprocessor.
     *
     * @param iface interface class
     * @return data access interface implementation object working
     * in current transaction
     */
    public final <T extends IDBCommon> T getInterface(Class<T> iface) {
        if (test != null) {
            return test.getNullInterface(iface);
        } else {
            return ctx.transaction.getInterface(iface, false);
        }
    }

    private <T> TypeMapper<T> getMapper(Class<T> cls) {
        return ctx.global.mappers.getMapper(cls);
    }

    public interface RowFetcher<T> {

        T fetchNext() throws SQLException;
    }

    /**
     * Fetches rows from result set.
     *
     * @param rowType row type class
     */
    public final <T> RowFetcher<T> getRowFetcher(Class<T> rowType, ResultSet rs) throws SQLException {
        boolean meta = false;
        if (test != null) {
            return () -> {
                test.getRowTypeFields(rowType, rs, meta);
                return null;
            };
        } else {
            RowTypeFactory<T> factory = ctx.global.getRowTypeFactory(rowType, meta);
            return () -> {
                if (rs.next()) {
                    return factory.fetch(ctx.global.mappers, rs);
                } else {
                    return null;
                }
            };
        }
    }

    public final GlobalContext getGlobal() {
        return ctx.global;
    }
}
