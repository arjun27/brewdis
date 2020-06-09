describe('brewdis tests', () => {
  beforeAll(async () => {
    await context.grantPermissions(['geolocation']);
    await context.setGeolocation({latitude: 34.0522, longitude: -118.2437}); // Los Angeles
    await page.goto('https://brewredis-spring-storefront.azuremicroservices.io/');
  });

  it('should have correct title', async () => {
    expect(await page.title()).toBe('Brewdis');
  });

  it('should be able to search', async () => {
    await page.fill('[placeholder=Search]', 'ipa');
    page.on('response', (response) => {console.log('response event', response.url())});
    await Promise.all([
      page.keyboard.press('Enter'),
      page.waitForSelector('mat-card-title')
    ]);
    await page.waitForLoadState('networkidle');
    console.log('networkidle');
    await page.screenshot({ path: '__tests__/artifacts/search.png' });
  });

  it('shows map', async () => {
    await Promise.all([
      page.click('text=Availability'),
      page.waitForSelector('img[src="assets/store-low.svg"]')
    ]);
    await page.screenshot({ path: '__tests__/artifacts/map.png' });
  });
})