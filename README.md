# Jupyter SQL Cell Variables

A PyCharm plugin that lets Jupyter **SQL cells use Python variables**. Write `{{ name }}` in a SQL cell, and when the cell runs, the value comes from the notebook's running kernel. The query still runs through PyCharm's own SQL cell support, over the data source picked in the cell, so there's no separate database connection to set up in code.

It addresses [PY-92307](https://youtrack.jetbrains.com/issue/PY-92307), which asks for Python variables in SQL cell query text.

```python
# Python cell
user_id = 42
products = ["Keyboard", "Monitor"]
```

```sql
-- SQL cell, data source "shop"
SELECT * FROM orders
WHERE user_id = {{ user_id }}
  AND product IN {{ products }}
```

The data source receives `... WHERE user_id = 42 AND product IN ('Keyboard', 'Monitor')`, and the result lands in the cell's dataframe variable as usual.
<img width="1380" height="734" alt="image" src="https://github.com/user-attachments/assets/aa2648ba-d44f-4663-9647-acd960985052" />


## Installation

Install it from JetBrains Marketplace (**Settings | Plugins | Marketplace**, search for "Jupyter SQL Cell Variables"), or from disk with **Settings | Plugins | ⚙ | Install Plugin from Disk…** and the zip from `build/distributions/`. Then restart PyCharm.

Nothing needs configuring. The plugin registers everything it needs: query rewriting, the editor's placeholder support, and error handling.

**Requirements:** PyCharm 2026.2 with Jupyter SQL cells available (Database Tools and SQL enabled), and a Python notebook kernel.

## Syntax

| SQL cell | Python value | SQL sent |
|---|---|---|
| `{{ user_id }}` | `42`, `1.5`, `Decimal("9.99")` | `42`, `1.5`, `9.99` |
| `{{ name }}` | `"O'Brien"` | `'O''Brien'` |
| `{{ flag }}` | `True`, `None` | `TRUE`, `NULL` |
| `{{ since }}` | `date(2026, 10, 1)`, `datetime(...)`, `pd.Timestamp(...)` | `'2026-10-01'`, `'2026-10-01 12:30:00'` |
| `IN {{ ids }}` | list, tuple, set, NumPy array, pandas Series or Index | `IN (1, 2, 3)` |
| `VALUES {{ rows }}` | `[("web", 1), ("ios", 2)]` | `VALUES ('web', 1), ('ios', 2)` |
| `'%{{ name }}%'` | `"it's"` | `'%it''s%'` (escaped text, no extra quotes) |
| `"{{ column }}"` | `"total sales"`, `'my "col"'` | `"total sales"`, `"my ""col"""` (escaped identifier, no extra quotes) |
| `{{ table \| raw }}` | `"sales.orders"` | `sales.orders` (unescaped `str(value)`) |
| `{{ cfg["start"] }}` | any Python expression | its value |

Rules:

- Placeholders inside `--` and `/* */` comments are left alone.
- NaN and infinity, empty collections, and types with no SQL literal (such as arbitrary objects) are rejected with an error instead of producing bad SQL.
- When a placeholder can't be evaluated, the cell shows a one-line `SqlCellVariableError` and the query is not sent. For example: `SqlCellVariableError: NameError: name 'x' is not defined in {{ x }}`.

## How it works

```
Run SQL cell
   │
   ▼
TemplatedSqlTaskBuilder        runs first; claims SQL cells that contain {{ ... }}
   │                           (every other cell goes to PyCharm unchanged)
   ▼
TemplatedSqlTask.doExecuteTask runs when the cell's turn comes, so Run All sees fresh values
   ├─ KernelScript             silent kernel request: evaluate each expression → SQL literal
   ├─ SqlTemplate.render       put the literals into the query text
   └─ NativeSqlTasks           hand the final SQL to PyCharm's own SQL cell task:
                               same data source, connection, result dataframe and output
```

In the editor, `PlaceholderPatternProvider` registers `{{ ... }}` as a Database Tools parameter, so the SQL parser accepts it. `PlaceholderErrorFilter` hides the parse errors left over in positions where SQL doesn't allow a single value, such as `VALUES {{ rows }}`.

Values are inserted as escaped SQL literals, not bound as driver parameters, because PyCharm's SQL cell task takes query text. String escaping doubles single quotes. `| raw` skips escaping on purpose, so use it only with trusted values.
