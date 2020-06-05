describe('brewdis tests', () => {
  beforeAll(async () => {
    await context.grantPermissions(['geolocation']);
    await context.setGeolocation({latitude: 59.95, longitude: 30.31667}); // St. Petersburg
    await page.goto('http://localhost:4200/');
  })

  it('should have correct title', async () => {
    expect(await page.title()).toBe('Brewdis');
  })

  it('shows map', async () => {
    await Promise.all([
      page.click('text=Availability'),
      page.waitForLoadState('networkidle')
    ]);
    await page.screenshot({ path: '__tests__/artifacts/screenshot.png' });
  })
})