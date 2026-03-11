import { useEffect, useState } from 'react';
import {
  Grid,
  Column,
  DataTable,
  Table,
  TableHead,
  TableRow,
  TableHeader,
  TableBody,
  TableCell,
  Tag,
  SkeletonText,
  Tile,
  InlineNotification,
} from '@carbon/react';
import { gatewayApi } from '../services/gatewayApi';

export default function RoutesPage() {
  const [routes, setRoutes] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    const fetchRoutes = async () => {
      try {
        const data = await gatewayApi.getRoutes();
        const items = Array.isArray(data)
          ? data
          : typeof data === 'object'
          ? Object.entries(data).map(([key, val]: [string, any]) => ({
              id: key,
              ...val,
            }))
          : [];
        setRoutes(items);
      } catch (err: any) {
        setError(err.message || 'Failed to load routes');
        // Try actuator gateway routes as fallback
        try {
          const actuatorRoutes = await gatewayApi.getGatewayRoutes();
          const items = Array.isArray(actuatorRoutes)
            ? actuatorRoutes.map((r: any, i: number) => ({
                id: r.route_id || `route-${i}`,
                path: r.predicate || '—',
                uri: r.uri || '—',
                enabled: true,
                order: r.order,
                filters: r.filters?.join(', ') || '—',
              }))
            : [];
          setRoutes(items);
          setError('');
        } catch {
          // keep the original error
        }
      } finally {
        setLoading(false);
      }
    };
    fetchRoutes();
  }, []);

  if (loading) {
    return (
      <Grid>
        <Column lg={16}>
          <SkeletonText heading width="30%" />
          <SkeletonText paragraph lineCount={6} />
        </Column>
      </Grid>
    );
  }

  const headers = [
    { key: 'id', header: 'Route ID' },
    { key: 'path', header: 'Path' },
    { key: 'uri', header: 'Target URI' },
    { key: 'enabled', header: 'Status' },
  ];

  const rows = routes.map((r) => ({
    id: r.id || r.route_id || String(Math.random()),
    path: r.path || r.predicate || '—',
    uri: r.uri || '—',
    enabled: r.enabled ?? true,
  }));

  return (
    <Grid>
      <Column lg={16} md={8} sm={4}>
        <h1 style={{ marginBottom: '1.5rem' }}>Gateway Routes</h1>
      </Column>

      {error && (
        <Column lg={16}>
          <InlineNotification
            kind="warning"
            title="Warning"
            subtitle={error}
            style={{ marginBottom: '1rem' }}
          />
        </Column>
      )}

      <Column lg={16} md={8} sm={4}>
        {rows.length === 0 ? (
          <Tile>
            <p style={{ color: '#8d8d8d' }}>No routes configured</p>
          </Tile>
        ) : (
          <DataTable rows={rows} headers={headers}>
            {({ rows: tableRows, headers: tableHeaders, getTableProps, getHeaderProps, getRowProps }: any) => (
              <Table {...getTableProps()}>
                <TableHead>
                  <TableRow>
                    {tableHeaders.map((h: any) => (
                      <TableHeader key={h.key} {...getHeaderProps({ header: h })}>
                        {h.header}
                      </TableHeader>
                    ))}
                  </TableRow>
                </TableHead>
                <TableBody>
                  {tableRows.map((row: any) => (
                    <TableRow key={row.id} {...getRowProps({ row })}>
                      {row.cells.map((cell: any) => (
                        <TableCell key={cell.id}>
                          {cell.info.header === 'enabled' ? (
                            <Tag type={cell.value ? 'green' : 'red'}>
                              {cell.value ? 'Active' : 'Disabled'}
                            </Tag>
                          ) : (
                            cell.value
                          )}
                        </TableCell>
                      ))}
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </DataTable>
        )}
      </Column>
    </Grid>
  );
}
